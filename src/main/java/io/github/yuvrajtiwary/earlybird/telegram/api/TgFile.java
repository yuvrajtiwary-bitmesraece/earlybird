package io.github.yuvrajtiwary.earlybird.telegram.api;

/** From getFile; download from /file/bot&lt;token&gt;/&lt;filePath&gt;. */
public record TgFile(String fileId, Long fileSize, String filePath) {}
