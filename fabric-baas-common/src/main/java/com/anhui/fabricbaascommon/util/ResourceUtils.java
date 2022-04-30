package com.anhui.fabricbaascommon.util;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

public class ResourceUtils {
    public static String release(byte[] data, String filetype) throws IOException {
        String downloadUrl = String.format("/download/%s.%s", UUID.randomUUID(), filetype);
        File output = new File("static" + downloadUrl);
        FileUtils.writeByteArrayToFile(output, data);
        return downloadUrl;
    }

    public static String release(File file, String filetype) throws IOException {
        String downloadUrl = String.format("/download/%s.%s", UUID.randomUUID(), filetype);
        File output = new File("static" + downloadUrl);
        FileUtils.copyFile(file, output);
        return downloadUrl;
    }
}
