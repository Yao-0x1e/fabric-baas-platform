package com.anhui.fabricbaascommon.bean;

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
        assert tlsRootCert.isFile();
        this.address = address;
        this.tlsRootCert = tlsRootCert;
    }

    public void assertRootCert() throws CertfileException {
        if (!tlsRootCert.isFile()) {
            throw new CertfileException("非法的TLS证书路径：" + tlsRootCert);
        }
    }
}