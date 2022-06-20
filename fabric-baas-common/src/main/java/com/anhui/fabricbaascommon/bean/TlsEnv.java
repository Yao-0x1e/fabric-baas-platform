package com.anhui.fabricbaascommon.bean;

import cn.hutool.core.lang.Assert;
import com.anhui.fabricbaascommon.exception.CertfileException;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.File;

@NoArgsConstructor
@Data
public class TlsEnv {
    private String address;
    private File tlsRootCert;

    public TlsEnv(String address, File tlsRootCert) {
        Assert.isTrue(tlsRootCert.isFile());
        this.address = address;
        this.tlsRootCert = tlsRootCert;
    }

    public void assertTlsCert() throws CertfileException {
        if (!tlsRootCert.isFile()) {
            throw new CertfileException("非法的TLS证书路径：" + tlsRootCert);
        }
    }
}