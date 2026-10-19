package com.example.accounting.ocr;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@Profile("test")
@Primary
public class FakeOcrProvider implements OcrProvider {

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public OcrData extractText(Path filePath, String contentType, String originalFileName) throws OcrException {
        String filename = originalFileName != null ? originalFileName : (filePath.getFileName() != null ? filePath.getFileName().toString() : "unknown");
        String lower = filename.toLowerCase();

        // Also check file content for corrupt marker
        try {
            String content = java.nio.file.Files.readString(filePath);
            if (content.toLowerCase().contains("corrupt")) {
                throw new OcrException("Simulated corrupt file: " + filename);
            }
        } catch (java.io.IOException ignored) {}

        // Simulate different behaviors based on filename
        if (lower.contains("corrupt") || lower.contains("invalid")) {
            throw new OcrException("Simulated corrupt file: " + filename);
        }
        if (lower.contains("unsupported")) {
            throw new OcrException("Unsupported file type simulation");
        }

        // Determine page count based on content type
        int pageCount = 1;
        String lowerType = contentType != null ? contentType.toLowerCase() : "";
        if (lowerType.contains("pdf")) {
            // For testing, simulate multi-page if filename contains "multi"
            if (lower.contains("multi") || lower.contains("3pages") || lower.contains("2pages")) {
                if (lower.contains("3pages")) pageCount = 3;
                else if (lower.contains("2pages")) pageCount = 2;
                else pageCount = 2;
            } else {
                pageCount = 1;
            }
            String text = "[Fake OCR] Extracted text from PDF: " + filename + " - Content: Invoice #INV-001 Total: 1000.00";
            return new OcrData(text, pageCount);
        } else if (lowerType.startsWith("image/")) {
            String text = "[Fake OCR] Extracted text from image: " + filename + " - Content: Receipt Total 500.00";
            return new OcrData(text, 1);
        } else {
            return new OcrData("[Fake OCR] Extracted text from: " + filename, 1);
        }
    }

    @Override
    public OcrData extractText(Path filePath, String contentType) throws OcrException {
        return extractText(filePath, contentType, filePath.getFileName() != null ? filePath.getFileName().toString() : "");
    }
}
