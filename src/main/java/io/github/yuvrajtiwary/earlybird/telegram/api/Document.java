package io.github.yuvrajtiwary.earlybird.telegram.api;

public record Document(String fileId, String fileUniqueId, String fileName, String mimeType, Long fileSize) {}
