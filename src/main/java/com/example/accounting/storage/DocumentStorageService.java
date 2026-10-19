package com.example.accounting.storage;

import java.io.InputStream;
import java.nio.file.Path;

public interface DocumentStorageService {

    String store(InputStream inputStream, String storageKey, String contentType, long fileSize);

    InputStream retrieve(String storageKey);

    Path retrieveAsPath(String storageKey);

    boolean exists(String storageKey);

    void delete(String storageKey);

    long size(String storageKey);
}
