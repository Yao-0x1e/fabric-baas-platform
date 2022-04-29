package com.anhui.fabricbaasttp.service;

import com.anhui.fabricbaascommon.bean.*;
import com.anhui.fabricbaascommon.constant.CertfileType;
import com.anhui.fabricbaascommon.exception.*;
import com.anhui.fabricbaascommon.fabric.ChannelUtils;
import com.anhui.fabricbaascommon.fabric.ConfigtxUtils;
import com.anhui.fabricbaascommon.request.BaseChannelRequest;
import com.anhui.fabricbaascommon.response.ListResult;
import com.anhui.fabricbaascommon.response.ResourceResult;
import com.anhui.fabricbaascommon.response.UniqueResult;
import com.anhui.fabricbaascommon.service.CaClientService;
import com.anhui.fabricbaascommon.service.MinioService;
import com.anhui.fabricbaascommon.util.*;
import com.anhui.fabricbaasttp.bean.Orderer;
import com.anhui.fabricbaasttp.bean.Peer;
import com.anhui.fabricbaasttp.constant.MinIOBucket;
import com.anhui.fabricbaasttp.entity.ChannelEntity;
import com.anhui.fabricbaasttp.entity.NetworkEntity;
import com.anhui.fabricbaasttp.repository.ChannelRepo;
import com.anhui.fabricbaasttp.request.ChannelCreateRequest;
import com.anhui.fabricbaasttp.request.ChannelGenerateInvitationCodeRequest;
import com.anhui.fabricbaasttp.request.ChannelPeerOperateRequest;
import com.anhui.fabricbaasttp.request.ChannelSubmitInvitationCodesRequest;
import com.anhui.fabricbaasttp.response.InvitationCodeResult;
import com.anhui.fabricbaasttp.util.IdentifierGenerator;
import com.anhui.fabricbaasweb.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * TODO: 对传入通道名称不能为系统通道名称
 * TODO: 让不同网络里面的通道可以重名
 */
@Service
@Slf4j
public class ChannelService {
    @Autowired
    private NetworkService networkService;
    @Autowired
    private ChannelRepo channelRepo;
    @Autowired
    private MinioService minioService;
    @Autowired
    private CaClientService caClientService;
    @Autowired
    private FabricEnvService fabricEnvService;

    private void assertOrganizationInChannel(ChannelEntity channel, String orgName) throws OrganizationException {
        if (!channel.getOrganizationNames().contains(orgName)) {
            throw new OrganizationException("当前组织不是通道的合法成员");
        }
    }

    private ChannelEntity getChannelOrThrowException(String channelName) throws ChannelException {
        Optional<ChannelEntity> channelOptional = channelRepo.findById(channelName);
        if (channelOptional.isEmpty()) {
            throw new ChannelException("未找到相应名称的通道");
        }
        return channelOptional.get();
    }

    public String getPeerIdentifierOrThrowException(ChannelEntity channel, Node peer) throws NodeException {
        List<Peer> peers = channel.getPeers();
        for (int i = 0, size = peers.size(); i < size; i++) {
            Peer endpoint = peers.get(i);
            if (endpoint.getHost().equals(peer.getHost()) &&
                    endpoint.getPort() == peer.getPort()) {
                return IdentifierGenerator.ofPeer(channel.getName(), i);
            }
        }
        throw new NodeException("未找到相应的Peer");
    }

    private static Node findNodeByAddress(Node node, List<? extends Node> nodes) {
        String target = node.addr();
        for (Node item : nodes) {
            if (item.addr().equals(target)) {
                return item;
            }
        }
        return null;
    }

    private void signEnvelopeWithOrganizations(String networkName, List<String> organizationNames, File envelope) throws IOException, InterruptedException, EnvelopeException {
        for (String orgName : organizationNames) {
            MspEnv organizationMspDir = fabricEnvService.buildMspEnvForOrg(networkName, orgName);
            log.info(String.format("正在使用%s在%s网络的管理员证书对Envelope进行签名：", orgName, networkName) + envelope.getAbsolutePath());
            ChannelUtils.signEnvelope(organizationMspDir, envelope);
        }
    }

    private void assertInvitationCodes(List<String> invitationCodes, List<String> invitorOrgNames, String inviteeOrgName, String channelName) throws Exception {
        Set<String> actualInvitorSet = new HashSet<>();
        Set<String> givenInvitorSet = new HashSet<>(invitorOrgNames);
        log.info("邀请码应包含组织：" + givenInvitorSet);
        for (String invitationCode : invitationCodes) {
            Invitation invitation = InvitationUtils.parseCode(invitationCode);
            if (!invitation.getChannelName().equals(channelName)) {
                throw new InvitationException("邀请码所包含的通道名称错误");
            }
            if (!invitation.getInviteeOrgName().equals(inviteeOrgName)) {
                throw new InvitationException("邀请码所包含的受邀组织错误");
            }
            // TODO: 增加时间检查
            actualInvitorSet.add(invitation.getInvitorOrgName());
        }
        log.info("邀请码实际包含的组织：" + actualInvitorSet);
        if (!actualInvitorSet.equals(givenInvitorSet)) {
            throw new InvitationException("必须包含所有通道中的组织的邀请码");
        }
    }

    public void createChannel(ChannelCreateRequest request) throws Exception {
        // 检查操作的组织是否属于相应的网络
        String curOrgName = SecurityUtils.getUsername();
        NetworkEntity network = networkService.getNetworkOrThrowException(request.getNetworkName());
        networkService.assertOrganizationInNetwork(network, curOrgName);

        // 检查相同名称的通道是否已经存在
        Optional<ChannelEntity> channelOptional = channelRepo.findById(request.getChannelName());
        if (channelOptional.isPresent()) {
            throw new ChannelException("相同名称的通道已存在");
        }

        // 默认配置该网络中当前的所有Orderer作为排序节点
        String organizationCertfileId = IdentifierGenerator.ofCertfile(request.getNetworkName(), curOrgName);
        File organizationCertfileDir = CertfileUtils.getCertfileDir(organizationCertfileId, CertfileType.ADMIN);
        ConfigtxOrganization ordererConfigtxOrg = new ConfigtxOrganization(
                caClientService.getCaOrganizationName(),
                caClientService.getCaOrganizationName(),
                new File(caClientService.getRootCertfileDir() + "/msp")
        );
        log.info("生成Orderer组织的配置：" + ordererConfigtxOrg);

        ConfigtxOrganization currentConfigtxOrg = new ConfigtxOrganization(curOrgName, curOrgName, new File(organizationCertfileDir + "/msp"));
        List<ConfigtxOrganization> configtxOrganizations = Collections.singletonList(currentConfigtxOrg);
        log.info("生成当前组织的配置：" + currentConfigtxOrg);

        List<ConfigtxOrderer> configtxOrderers = new ArrayList<>(network.getOrderers().size());
        for (Orderer endpoint : network.getOrderers()) {
            File tlsServerCrt = new File(CertfileUtils.getCertfileDir(endpoint.getCaUsername(), CertfileType.ORDERER) + "/tls/server.crt");

            ConfigtxOrderer configtxOrderer = new ConfigtxOrderer();
            configtxOrderer.setHost(endpoint.getHost());
            configtxOrderer.setPort(endpoint.getPort());
            configtxOrderer.setServerTlsCert(tlsServerCrt);
            configtxOrderer.setClientTlsCert(tlsServerCrt);
            configtxOrderers.add(configtxOrderer);
        }
        log.info("生成通道的Orderer节点配置：" + configtxOrderers);

        // 生成configtx.yaml配置文件
        File configtxDir = SimpleFileUtils.createTempDir();
        File configtxYaml = new File(configtxDir + "/configtx.yaml");
        ConfigtxUtils.generateConfigtx(configtxYaml, network.getConsortiumName(), configtxOrderers, ordererConfigtxOrg, configtxOrganizations);
        log.info(String.format("生成基本configtx.yaml文件：%s", configtxYaml.getAbsolutePath()));
        ConfigtxUtils.appendChannelToConfigtx(configtxYaml, request.getChannelName(), Collections.singletonList(curOrgName));
        log.info(String.format("将通道配置加入configtx.yaml文件：%s", configtxYaml.getAbsolutePath()));

        // 默认选择第一个Orderer
        // TODO: 选择后面动态加入的Orderer可会引发报错 Error: got unexpected status: SERVICE_UNAVAILABLE -- channel syschannel is not serviced by me
        Orderer orderer = network.getOrderers().get(0);
        log.info("随机从网络中选择Orderer：" + orderer);

        // 创建通道
        MspEnv organizationMspEnv = fabricEnvService.buildMspEnvForOrg(network.getName(), curOrgName);
        TlsEnv ordererTlsEnv = fabricEnvService.buildTlsEnvForOrderer(orderer);
        log.info("生成组织的MSP环境变量：" + organizationMspEnv);
        log.info("生成Orderer的TLS环境变量：" + ordererTlsEnv);
        File appChannelGenesis = SimpleFileUtils.createTempFile("block");
        ChannelUtils.createChannel(organizationMspEnv, ordererTlsEnv, configtxDir, request.getChannelName(), appChannelGenesis);

        // 将通道创世区块保存至MinIO
        String channelId = IdentifierGenerator.ofChannel(request.getNetworkName(), request.getChannelName());
        minioService.putFile(MinIOBucket.APP_CHANNEL_GENESIS_BLOCK_BUCKET_NAME, channelId, appChannelGenesis);
        log.info("将创世区块保存到MinIO：" + appChannelGenesis.getAbsolutePath());

        // 将通道信息保存至MongoDB
        ChannelEntity channel = new ChannelEntity();
        channel.setName(request.getChannelName());
        channel.setNetworkName(request.getNetworkName());
        channel.setOrganizationNames(Collections.singletonList(curOrgName));
        channel.setPeers(Collections.emptyList());
        channelRepo.save(channel);
        log.info("保存通道信息：" + channel);
    }

    public byte[] queryGenesisBlock(String channelName) throws Exception {
        ChannelEntity channel = getChannelOrThrowException(channelName);
        String curOrgName = SecurityUtils.getUsername();
        assertOrganizationInChannel(channel, curOrgName);
        Orderer orderer = RandomUtils.select(networkService.getOrderers(channel.getNetworkName()));

        File block = SimpleFileUtils.createTempFile("block");

        CoreEnv ordererCoreEnv = fabricEnvService.buildCoreEnvForOrderer(orderer);
        ChannelUtils.fetchGenesisBlock(ordererCoreEnv, channelName, block);
        return FileUtils.readFileToByteArray(block);
    }

    public void submitInvitationCodes(ChannelSubmitInvitationCodesRequest request) throws Exception {
        String curOrgName = SecurityUtils.getUsername();
        ChannelEntity channel = getChannelOrThrowException(request.getChannelName());
        if (channel.getOrganizationNames().contains(curOrgName)) {
            throw new OrganizationException("组织已经是通道的合法成员");
        }

        // 对邀请码进行验证
        assertInvitationCodes(request.getInvitationCodes(), channel.getOrganizationNames(), curOrgName, channel.getName());

        // 从通道中随机选择一个Orderer
        Orderer orderer = RandomUtils.select(networkService.getOrderers(channel.getNetworkName()));
        log.info("随机选择Orderer节点：" + orderer);

        // 拉取指定通道的配置
        CoreEnv ordererCoreEnv = fabricEnvService.buildCoreEnvForOrderer(orderer);
        log.info("生成Orderer环境变量：" + ordererCoreEnv);
        File oldChannelConfig = SimpleFileUtils.createTempFile("json");
        ChannelUtils.fetchConfig(ordererCoreEnv, channel.getName(), oldChannelConfig);
        log.info(String.format("拉取通道配置：%s", channel.getName()));

        // 生成新组织的配置
        File newOrgConfigtxDir = SimpleFileUtils.createTempDir();
        File newOrgConfigtxYaml = new File(newOrgConfigtxDir + "/configtx.yaml");
        File newOrgConfigtxJson = SimpleFileUtils.createTempFile("json");
        String newOrgCertfileId = IdentifierGenerator.ofCertfile(channel.getNetworkName(), curOrgName);
        File newOrgCertfileDir = CertfileUtils.getCertfileDir(newOrgCertfileId, CertfileType.ADMIN);

        ConfigtxOrganization configtxOrganization = new ConfigtxOrganization();
        configtxOrganization.setId(curOrgName);
        configtxOrganization.setName(curOrgName);
        configtxOrganization.setMspDir(new File(newOrgCertfileDir + "/msp"));
        ConfigtxUtils.generateOrgConfigtx(newOrgConfigtxYaml, configtxOrganization);
        log.info("生成新组织的configtx.yaml配置：" + newOrgConfigtxYaml.getAbsolutePath());
        ConfigtxUtils.convertOrgConfigtxToJson(newOrgConfigtxJson, newOrgConfigtxDir, curOrgName);
        log.info("将新组织的configtx.yaml转换为json格式：" + newOrgConfigtxJson.getAbsolutePath());

        // 对通道配置文件进行更新并生成Envelope
        File newChannelConfig = SimpleFileUtils.createTempFile("json");
        FileUtils.copyFile(oldChannelConfig, newChannelConfig);
        ChannelUtils.appendOrganizationToAppChannelConfig(curOrgName, newOrgConfigtxJson, newChannelConfig);
        log.info("将新组织添加到现有的通道配置中：" + newChannelConfig.getAbsolutePath());
        File envelope = SimpleFileUtils.createTempFile("pb");
        ChannelUtils.generateEnvelope(channel.getName(), envelope, oldChannelConfig, newChannelConfig);
        log.info("生成向通道添加组织的Envelope：" + envelope.getAbsolutePath());

        // 使用该通道中所有组织的身份对Envelope进行签名（包括未加入的组织）
        List<String> channelOrganizationNames = channel.getOrganizationNames();
        List<String> signerOrgNames = channelOrganizationNames.subList(0, channelOrganizationNames.size() - 1);
        signEnvelopeWithOrganizations(channel.getNetworkName(), signerOrgNames, envelope);

        // 将Envelope提交到Orderer
        // 注意不能用未在通道中的组织的身份来提交通道更新，即使通道中的组织全都签名了也依然会报错
        // 提交更新的组织会同时进行签名，所以提交更新的组织不用参与签名的签名过程
        String lastChannelOrgName = channelOrganizationNames.get(channelOrganizationNames.size() - 1);
        MspEnv organizationMspEnv = fabricEnvService.buildMspEnvForOrg(channel.getNetworkName(), lastChannelOrgName);
        log.info("生成提交Envelope的当前组织的MSP环境变量：" + organizationMspEnv);
        ChannelUtils.submitChannelUpdate(organizationMspEnv, ordererCoreEnv.getTlsEnv(), channel.getName(), envelope);

        // 将组织保存至MongoDB
        channel.getOrganizationNames().add(curOrgName);
        log.info("更新通道信息：" + channel);
        channelRepo.save(channel);
    }

    public void joinChannel(ChannelPeerOperateRequest request, MultipartFile peerCertZip) throws Exception {
        String curOrgName = SecurityUtils.getUsername();
        ChannelEntity channel = getChannelOrThrowException(request.getChannelName());

        // 对证书格式进行检查
        CertfileUtils.assertCertfileZip(peerCertZip);

        // 检查Peer是否在通道中且为该组织的节点
        Peer peer = (Peer) findNodeByAddress(request.getPeer(), channel.getPeers());
        if (peer != null) {
            throw new NodeException("相应的Peer节点已经加入了通道");
        }
        // 生成新Peer的信息
        int newPeerNo = channel.getPeers().size();
        String newPeerId = IdentifierGenerator.ofPeer(channel.getName(), newPeerNo);
        peer = new Peer();
        peer.setName(newPeerId);
        peer.setHost(request.getPeer().getHost());
        peer.setPort(request.getPeer().getPort());
        peer.setOrganizationName(curOrgName);
        log.info("生成新Peer信息：" + peer);

        // 随机选择一个Orderer
        Orderer orderer = RandomUtils.select(networkService.getOrderers(channel.getNetworkName()));
        log.info("随机选择Orderer节点：" + orderer);

        // 拉取通道的创世区块
        CoreEnv ordererCoreEnv = fabricEnvService.buildCoreEnvForOrderer(orderer);
        log.info("生成Orderer的环境变量参数：" + ordererCoreEnv);
        File channelGenesisBlock = SimpleFileUtils.createTempFile("block");
        ChannelUtils.fetchGenesisBlock(ordererCoreEnv, channel.getName(), channelGenesisBlock);
        log.info(String.format("拉取通道%s的启动区块：", channel.getName()) + channelGenesisBlock.getAbsolutePath());

        // 解压Peer证书到临时目录
        File peerCertfileZip = SimpleFileUtils.createTempFile("zip");
        FileUtils.writeByteArrayToFile(peerCertfileZip, peerCertZip.getBytes());
        log.info("将用户上传的Peer证书保存至：" + peerCertfileZip.getAbsolutePath());
        File peerCertTempCertfileDir = SimpleFileUtils.createTempDir();
        ZipUtils.unzip(peerCertfileZip, peerCertTempCertfileDir);
        log.info("将Peer证书解压到：" + peerCertTempCertfileDir.getAbsolutePath());
        CertfileUtils.assertCertfile(peerCertTempCertfileDir);

        // 尝试将Peer加入到通道中（TLS环境变量需要改为临时目录中的，因为还没保存到正式目录）
        CoreEnv peerCoreEnv = fabricEnvService.buildCoreEnvForPeer(channel.getNetworkName(), curOrgName, peer);
        peerCoreEnv.setTlsRootCert(new File(peerCertTempCertfileDir + "/tls/ca.crt"));
        log.info("生成Peer的环境变量：" + peerCoreEnv);
        ChannelUtils.joinChannel(peerCoreEnv, channelGenesisBlock);

        // 将Peer证书保存到MinIO和证书目录
        minioService.putBytes(MinIOBucket.PEER_CERTFILE_BUCKET_NAME, peer.getName(), peerCertZip.getBytes());
        log.info("将用户上传的Peer证书保存至MinIO：" + peerCertZip.getOriginalFilename());
        File formalPeerCertfileDir = CertfileUtils.getCertfileDir(peer.getName(), CertfileType.PEER);
        ZipUtils.unzip(peerCertfileZip, formalPeerCertfileDir);
        log.info("将Peer证书保存到正式目录：" + formalPeerCertfileDir.getAbsolutePath());

        // 更新MongoDB中的信息
        channel.getPeers().add(peer);
        channelRepo.save(channel);
        log.info("更新通道信息：" + channel);
    }

    public InvitationCodeResult generateInvitationCode(ChannelGenerateInvitationCodeRequest request) throws Exception {
        // 检查组织是否是通道的合法成员
        String curOrgName = SecurityUtils.getUsername();
        ChannelEntity channel = getChannelOrThrowException(request.getChannelName());
        if (!channel.getOrganizationNames().contains(curOrgName)) {
            throw new OrganizationException("当前组织不存在于指定通道中");
        }

        // 检查被邀请的组织是否是合法的网络成员
        NetworkEntity network = networkService.getNetworkOrThrowException(channel.getNetworkName());
        if (!network.getOrganizationNames().contains(request.getOrganizationName())) {
            throw new OrganizationException("被邀请的组织不是合法的网络成员");
        }

        // 生成邀请码
        Invitation invitation = new Invitation();
        invitation.setInvitorOrgName(curOrgName);
        invitation.setInviteeOrgName(request.getOrganizationName());
        invitation.setChannelName(request.getChannelName());
        invitation.setTimestamp(System.currentTimeMillis());
        log.info("生成邀请信息：" + invitation);
        String invitationCode = InvitationUtils.getCode(invitation);
        log.info("生成邀请码：" + invitationCode);
        return new InvitationCodeResult(invitationCode);
    }

    public void setAnchorPeer(ChannelPeerOperateRequest request) throws Exception {
        String curOrgName = SecurityUtils.getUsername();
        ChannelEntity channel = getChannelOrThrowException(request.getChannelName());
        if (!channel.getOrganizationNames().contains(curOrgName)) {
            throw new OrganizationException("组织不是通道的合法成员");
        }

        // 检查Peer是否在通道中且为该组织的节点
        Peer peer = (Peer) findNodeByAddress(request.getPeer(), channel.getPeers());
        if (peer == null) {
            throw new NodeException("不存在相应的Peer：" + request.getPeer().addr());
        } else if (!peer.getOrganizationName().equals(curOrgName)) {
            throw new OrganizationException("Peer不属于当前组织：" + curOrgName);
        }

        // 从通道中随机选择一个Orderer
        Orderer orderer = RandomUtils.select(networkService.getOrderers(channel.getNetworkName()));
        log.info("随机选择Orderer节点：" + orderer);

        // 拉取指定通道的配置
        CoreEnv ordererCoreEnv = fabricEnvService.buildCoreEnvForOrderer(orderer);
        log.info("生成Orderer环境变量：" + ordererCoreEnv);
        File oldChannelConfig = SimpleFileUtils.createTempFile("json");
        ChannelUtils.fetchConfig(ordererCoreEnv, channel.getName(), oldChannelConfig);
        log.info("通道配置文件：" + oldChannelConfig.getAbsolutePath());

        // 对通道配置文件进行更新并生成Envelope
        File newChannelConfig = SimpleFileUtils.createTempFile("json");
        FileUtils.copyFile(oldChannelConfig, newChannelConfig);
        ChannelUtils.appendAnchorPeerToChannelConfig(request.getPeer(), curOrgName, oldChannelConfig);
        log.info("将锚节点添加到通道配置中：" + newChannelConfig.getAbsolutePath());
        File envelope = SimpleFileUtils.createTempFile("pb");
        ChannelUtils.generateEnvelope(channel.getName(), envelope, oldChannelConfig, newChannelConfig);
        log.info("生成更新锚节点的Envelope：" + envelope.getAbsolutePath());

        // 将Envelope提交到Orderer
        MspEnv organizationMspEnv = fabricEnvService.buildMspEnvForOrg(channel.getNetworkName(), curOrgName);
        ChannelUtils.submitChannelUpdate(organizationMspEnv, ordererCoreEnv.getTlsEnv(), channel.getName(), envelope);
    }

    public ResourceResult queryPeerTlsCert(ChannelPeerOperateRequest request) throws Exception {
        // 检查网络是否存在
        Optional<ChannelEntity> channelOptional = channelRepo.findById(request.getChannelName());
        if (channelOptional.isEmpty()) {
            throw new ChannelException("不存在通道：" + request.getChannelName());
        }
        // 检查当前组织是否位于该网络中
        String curOrgName = SecurityUtils.getUsername();
        assertOrganizationInChannel(channelOptional.get(), curOrgName);

        String peerName = getPeerIdentifierOrThrowException(channelOptional.get(), request.getPeer());
        // 检查Orderer证书
        File ordererCertfileDir = CertfileUtils.getCertfileDir(peerName, CertfileType.PEER);
        CertfileUtils.assertCertfile(ordererCertfileDir);
        String downloadUrl = String.format("/download/cert/%s.crt", UUID.randomUUID());
        FileUtils.copyFile(new File(ordererCertfileDir + "/tls/ca.crt"), new File("static" + downloadUrl));

        ResourceResult result = new ResourceResult();
        result.setDownloadUrl(downloadUrl);
        return result;
    }

    public ListResult<Peer> queryPeers(BaseChannelRequest request) throws Exception {
        Optional<ChannelEntity> channelOptional = channelRepo.findById(request.getChannelName());
        if (channelOptional.isPresent()) {
            return new ListResult<>(channelOptional.get().getPeers());
        } else {
            throw new ChannelException("未找到通道：" + request.getChannelName());
        }
    }

    public UniqueResult<ChannelEntity> getChannel(BaseChannelRequest request) throws ChannelException {
        return new UniqueResult<>(getChannelOrThrowException(request.getChannelName()));
    }
}
