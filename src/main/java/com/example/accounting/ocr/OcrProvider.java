package com.example.accounting.ocr;

import java.nio.file.Path;

public interface OcrProvider {

    OcrData extractText(Path filePath, String contentType, String originalFileName) throws OcrException;

    boolean isAvailable();

    default OcrData extractText(Path filePath, String contentType) throws OcrException {
        return extractText(filePath, contentType, filePath.getFileName() != null ? filePath.getFileName().toString() : "");
    }
}
