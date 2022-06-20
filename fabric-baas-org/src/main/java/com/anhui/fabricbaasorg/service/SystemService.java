package com.anhui.fabricbaasorg.service;

import cn.hutool.core.lang.Assert;
import com.anhui.fabricbaascommon.bean.CsrConfig;
import com.anhui.fabricbaascommon.configuration.AdminConfiguration;
import com.anhui.fabricbaascommon.entity.CaEntity;
import com.anhui.fabricbaascommon.entity.UserEntity;
import com.anhui.fabricbaascommon.exception.CaException;
import com.anhui.fabricbaascommon.exception.DuplicatedOperationException;
import com.anhui.fabricbaascommon.fabric.CaUtils;
import com.anhui.fabricbaascommon.repository.CaRepo;
import com.anhui.fabricbaascommon.repository.UserRepo;
import com.anhui.fabricbaascommon.service.CaClientService;
import com.anhui.fabricbaascommon.service.CaServerService;
import com.anhui.fabricbaascommon.util.MyFileUtils;
import com.anhui.fabricbaasorg.entity.RemoteUserEntity;
import com.anhui.fabricbaasorg.remote.RemoteHttpClient;
import com.anhui.fabricbaasorg.remote.TTPOrganizationApi;
import com.anhui.fabricbaasorg.repository.RemoteUserRepo;
import com.spotify.docker.client.exceptions.DockerException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

@Slf4j
@Service
public class SystemService {
    @Autowired
    private KubernetesService kubernetesService;
    @Autowired
    private UserRepo userRepo;
    @Autowired
    private AdminConfiguration adminConfiguration;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private CaClientService caClientService;
    @Autowired
    private CaServerService caServerService;
    @Autowired
    private CaRepo caRepo;
    @Autowired
    private RemoteUserRepo remoteUserRepo;
    @Autowired
    private RemoteHttpClient remoteHttpClient;
    @Autowired
    private TTPOrganizationApi ttpOrganizationApi;

    public void initRemoteUser(RemoteUserEntity remoteUser) throws Exception {
        if (remoteUserRepo.count() != 0) {
            throw new DuplicatedOperationException("可信第三方的信息已经存在！");
        }
        remoteHttpClient.init(remoteUser.getApiServer());
        ttpOrganizationApi.login(remoteUser.getOrganizationName(), remoteUser.getPassword());
        remoteUserRepo.save(remoteUser);
    }

    public void initCaService(CaEntity ca) throws DuplicatedOperationException, DockerException, InterruptedException, IOException, CaException {
        if (caRepo.count() != 0) {
            throw new DuplicatedOperationException("CA服务存在，请勿重复初始化系统");
        }
        log.info("可信第三方信息：" + ca);
        CsrConfig csrConfig = CaUtils.buildCsrConfig(ca);
        log.info("生成CA服务信息：" + csrConfig);
        // 启动CA容器并尝试初始化管理员证书
        log.info("正在启动CA服务容器...");
        caServerService.initCaServer(csrConfig);
        log.info("正在初始化CA服务管理员证书...");
        caClientService.initRootCertfile(csrConfig);
        caRepo.save(ca);
    }

    public void initKubernetesService(MultipartFile kubernetesConfig) throws Exception {
        // 将证书导入到KubernetesService中
        File tempKubernetesConfig = MyFileUtils.createTempFile("yaml");
        FileUtils.writeByteArrayToFile(tempKubernetesConfig, kubernetesConfig.getBytes());
        kubernetesService.importAdminConfig(tempKubernetesConfig);
    }

    public void setAdminPassword(String password) {
        Optional<UserEntity> adminOptional = userRepo.findById(adminConfiguration.getDefaultUsername());
        Assert.isTrue(adminOptional.isPresent());
        adminOptional.ifPresent(admin -> {
            log.info("正在初始化管理员信息");
            String encodedPassword = passwordEncoder.encode(password);
            admin.setPassword(encodedPassword);
            userRepo.save(admin);
        });
    }

    /**
     * 主要包括以下内容
     * 1. 重置管理员的密码
     * 2. 将集群配置导入到Kubernetes服务
     * 3. 初始化CA服务
     * 4. 初始化TTP远程用户
     */
    @Transactional
    public void init(CaEntity org, RemoteUserEntity remoteUser, String adminPassword, MultipartFile kubernetesConfig) throws Exception {
        if (isAvailable()) {
            throw new DuplicatedOperationException("请勿重复初始化系统");
        }

        try {
            initRemoteUser(remoteUser);
            initKubernetesService(kubernetesConfig);
            initCaService(org);

            // 更新系统管理员密码
            if (adminPassword != null && !StringUtils.isBlank(adminPassword)) {
                setAdminPassword(adminPassword);
            }
        } catch (Exception e) {
            caServerService.cleanCaServer();
            throw e;
        }
    }

    public boolean isAvailable() {
        return caRepo.count() != 0;
    }
}
