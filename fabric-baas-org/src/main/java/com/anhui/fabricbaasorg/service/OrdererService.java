package com.anhui.fabricbaasorg.service;

import cn.hutool.core.util.ZipUtil;
import com.anhui.fabricbaascommon.bean.Node;
import com.anhui.fabricbaascommon.constant.CertfileType;
import com.anhui.fabricbaascommon.fabric.CertfileUtils;
import com.anhui.fabricbaascommon.response.PageResult;
import com.anhui.fabricbaascommon.service.CaClientService;
import com.anhui.fabricbaascommon.util.MyFileUtils;
import com.anhui.fabricbaasorg.entity.OrdererEntity;
import com.anhui.fabricbaasorg.remote.TTPNetworkApi;
import com.anhui.fabricbaasorg.remote.TTPOrganizationApi;
import com.anhui.fabricbaasorg.repository.OrdererRepo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.io.File;

@Slf4j
@Service
@CacheConfig(cacheNames = "org")
public class OrdererService {
    @Autowired
    private TTPNetworkApi ttpNetworkApi;
    @Autowired
    private KubernetesService kubernetesService;
    @Autowired
    private CaClientService caClientService;
    @Autowired
    private OrdererRepo ordererRepo;
    @Autowired
    private TTPOrganizationApi ttpOrganizationApi;

    public void startOrderer(String networkName, OrdererEntity orderer, File sysChannelGenesisBlock) throws Exception {
        // 获取集群域名
        String domain = caClientService.getCaOrganizationDomain();

        // 获取Orderer的证书并解压
        Node node = new Node(domain, orderer.getKubeNodePort());
        File ordererCertfileZip = MyFileUtils.createTempFile("zip");
        ttpNetworkApi.queryOrdererCert(networkName, node, ordererCertfileZip);
        File certfileDir = CertfileUtils.getCertfileDir(orderer.getName(), CertfileType.ORDERER);
        boolean mkdirs = certfileDir.mkdirs();
        ZipUtil.unzip(ordererCertfileZip, certfileDir);

        // 启动Orderer节点
        String ordererOrganizationName = ttpOrganizationApi.getOrdererOrganizationName();
        kubernetesService.startOrderer(ordererOrganizationName, orderer, certfileDir, sysChannelGenesisBlock);
    }


    public void startOrderer(String networkName, OrdererEntity orderer) throws Exception {
        // 获取网络的创世区块
        File sysChannelGenesisBlock = MyFileUtils.createTempFile("block");
        ttpNetworkApi.queryGenesisBlock(networkName, sysChannelGenesisBlock);
        startOrderer(networkName, orderer, sysChannelGenesisBlock);
    }

    public void stopOrderer(String ordererName) throws Exception {
        kubernetesService.stopOrderer(ordererName);
    }

    public PageResult<OrdererEntity> queryOrderersInCluster(int page, int pageSize) {
        Pageable pageable = PageRequest.of(page - 1, pageSize);
        return new PageResult<>(ordererRepo.findAll(pageable));
    }
}
