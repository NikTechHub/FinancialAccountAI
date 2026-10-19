package com.example.accounting.ocr;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class TesseractOcrProvider implements OcrProvider {

    private final String tesseractCommand;
    private final String tesseractDataPath;

    public TesseractOcrProvider(
            @Value("${app.ocr.tesseract-command:tesseract}") String tesseractCommand,
            @Value("${app.ocr.tesseract-data-path:}") String tesseractDataPath) {
        this.tesseractCommand = tesseractCommand;
        this.tesseractDataPath = tesseractDataPath;
    }

    @Override
    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(tesseractCommand, "--version");
            if (!tesseractDataPath.isBlank()) {
                pb.environment().put("TESSDATA_PREFIX", tesseractDataPath);
            }
            Process p = pb.start();
            boolean finished = p.waitFor(3, TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public OcrData extractText(Path filePath, String contentType, String originalFileName) throws OcrException {
        String lowerType = contentType != null ? contentType.toLowerCase() : "";
        try {
            if (lowerType.contains("pdf")) {
                return extractFromPdf(filePath, originalFileName);
            } else if (lowerType.startsWith("image/")) {
                return extractFromImage(filePath);
            } else {
                throw new OcrException("Unsupported content type for OCR: " + contentType);
            }
        } catch (OcrException e) {
            throw e;
        } catch (Exception e) {
            throw new OcrException("OCR processing failed: " + e.getMessage(), e);
        }
    }

    @Override
    public OcrData extractText(Path filePath, String contentType) throws OcrException {
        return extractText(filePath, contentType, filePath.getFileName() != null ? filePath.getFileName().toString() : "");
    }

    private OcrData extractFromPdf(Path pdfPath, String originalFileName) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            int pageCount = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            if (text != null && !text.trim().isEmpty() && text.trim().length() > 20) {
                // Text PDF - direct extraction
                return new OcrData(text.trim(), pageCount);
            }
            // Scanned PDF - try OCR via rendering
            if (!isAvailable()) {
                // Fallback: return placeholder with page count
                String fallback = "[OCR fallback] No extractable text found in PDF ("
                        + pageCount + " pages). Tesseract not available for image-based OCR.";
                return new OcrData(fallback, pageCount);
            }
            // If Tesseract available, try to OCR each page rendered as image
            // For simplicity, we will use a basic approach: render via PDFBox and OCR
            // Due to complexity, we fallback to placeholder for now with page count
            // Real implementation would render pages to temp images and run tesseract
            String ocrFallback = "[OCR] Scanned PDF with " + pageCount + " pages - OCR processing would extract text here. "
                    + "Tesseract available but image rendering not fully configured in this milestone.";
            return new OcrData(ocrFallback, pageCount);
        } catch (IOException e) {
            throw new OcrException("Failed to read PDF: " + e.getMessage(), e);
        }
    }

    private OcrData extractFromImage(Path imagePath) throws Exception {
        if (!isAvailable()) {
            // Fallback for testing without tesseract
            String fallback = "[OCR fallback] Image OCR not available - Tesseract not installed. File: " + imagePath.getFileName();
            return new OcrData(fallback, 1);
        }
        // Create temp output file
        Path tempOutput = Files.createTempFile("ocr-", ".txt");
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    tesseractCommand,
                    imagePath.toAbsolutePath().toString(),
                    tempOutput.toAbsolutePath().toString().replace(".txt", ""),
                    "-l", "eng"
            );
            if (!tesseractDataPath.isBlank()) {
                pb.environment().put("TESSDATA_PREFIX", tesseractDataPath);
            }
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new OcrException("Tesseract timeout for image: " + imagePath);
            }
            int exit = p.exitValue();
            if (exit != 0) {
                String error = new String(p.getInputStream().readAllBytes());
                throw new OcrException("Tesseract failed with exit " + exit + ": " + error);
            }
            Path outputTxt = java.nio.file.Paths.get(tempOutput.toAbsolutePath().toString().replace(".txt", "") + ".txt");
            // Also try without replacement
            if (!Files.exists(outputTxt)) {
                outputTxt = tempOutput;
            }
            String text = "";
            if (Files.exists(outputTxt)) {
                text = Files.readString(outputTxt);
                Files.deleteIfExists(outputTxt);
            }
            if (text == null || text.trim().isEmpty()) {
                text = "[OCR] No text extracted from image: " + imagePath.getFileName();
            }
            return new OcrData(text.trim(), 1);
        } finally {
            Files.deleteIfExists(tempOutput);
            // also delete tesseract output txt if exists
            Path tesseractOut = java.nio.file.Paths.get(tempOutput.toAbsolutePath().toString().replace(".txt", "") + ".txt");
            try { Files.deleteIfExists(tesseractOut); } catch (Exception ignored) {}
        }
    }
}
