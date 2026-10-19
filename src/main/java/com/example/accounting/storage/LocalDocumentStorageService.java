package com.example.accounting.storage;

import com.example.accounting.exception.DocumentStorageException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Service
public class LocalDocumentStorageService implements DocumentStorageService {

    private final Path rootLocation;

    public LocalDocumentStorageService(@Value("${app.documents.storage-path:./storage/documents}") String storagePath) {
        this.rootLocation = Paths.get(storagePath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.rootLocation);
        } catch (IOException e) {
            throw new DocumentStorageException("Could not initialize storage directory: " + storagePath, e);
        }
    }

    private Path resolveAndValidate(String storageKey) {
        // Prevent path traversal: storageKey must be relative and normalized stays within root
        Path resolved = rootLocation.resolve(storageKey).normalize();
        if (!resolved.startsWith(rootLocation)) {
            throw new DocumentStorageException("Path traversal attempt detected: " + storageKey);
        }
        return resolved;
    }

    @Override
    public String store(InputStream inputStream, String storageKey, String contentType, long fileSize) {
        Path destination = resolveAndValidate(storageKey);
        try {
            Files.createDirectories(destination.getParent());
            Files.copy(inputStream, destination, StandardCopyOption.REPLACE_EXISTING);
            return storageKey;
        } catch (IOException e) {
            throw new DocumentStorageException("Failed to store file: " + storageKey, e);
        }
    }

    @Override
    public InputStream retrieve(String storageKey) {
        Path file = resolveAndValidate(storageKey);
        if (!Files.exists(file)) {
            throw new DocumentStorageException("File not found: " + storageKey);
        }
        try {
            return Files.newInputStream(file);
        } catch (IOException e) {
            throw new DocumentStorageException("Failed to read file: " + storageKey, e);
        }
    }

    @Override
    public Path retrieveAsPath(String storageKey) {
        Path file = resolveAndValidate(storageKey);
        if (!Files.exists(file)) {
            throw new DocumentStorageException("File not found: " + storageKey);
        }
        return file;
    }

    @Override
    public boolean exists(String storageKey) {
        Path file = resolveAndValidate(storageKey);
        return Files.exists(file);
    }

    @Override
    public void delete(String storageKey) {
        Path file = resolveAndValidate(storageKey);
        try {
            Files.deleteIfExists(file);
            // clean up empty parent directories up to root
            Path parent = file.getParent();
            while (parent != null && !parent.equals(rootLocation) && Files.isDirectory(parent)) {
                try {
                    if (Files.list(parent).findAny().isEmpty()) {
                        Files.deleteIfExists(parent);
                        parent = parent.getParent();
                    } else {
                        break;
                    }
                } catch (IOException ignored) {
                    break;
                }
            }
        } catch (IOException e) {
            throw new DocumentStorageException("Failed to delete file: " + storageKey, e);
        }
    }

    @Override
    public long size(String storageKey) {
        Path file = resolveAndValidate(storageKey);
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new DocumentStorageException("Failed to get file size: " + storageKey, e);
        }
    }

    public Path getRootLocation() {
        return rootLocation;
    }
}
