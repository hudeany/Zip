package de.soderer.zip;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipException;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;

/**
 * Static helper methods for password secured zip files, based on the zip4j library.
 */
public class Zip4jUtilities {
	private Zip4jUtilities() {
		// Utility class with static methods only
	}

	/**
	 * Returns the names of all entries of a (password secured) zip file.
	 *
	 * @param file zip file to read
	 * @param zipPassword password of the zip file, may be null for unsecured zip files
	 * @return names of all entries, including directories
	 * @throws ZipException if the file is no valid zip file
	 * @throws IOException if the file does not exist or cannot be read
	 */
	public static List<String> getZipFileEntries(final File file, final char[] zipPassword) throws ZipException, IOException {
		checkZipFileExists(file);

		final List<String> entries = new ArrayList<>();
		try (ZipFile zipFile = new ZipFile(file, zipPassword)) {
			final List<FileHeader> fileHeaders = zipFile.getFileHeaders();
			if (fileHeaders != null) {
				for (final FileHeader fileHeader : fileHeaders) {
					entries.add(fileHeader.getFileName());
				}
			}
		}
		return entries;
	}

	/**
	 * Replaces a zip file containing exactly one file by a password secured zip file with the same content.
	 * The file inside is named like the zip file without its ".zip" extension and keeps its modification time.
	 *
	 * @param originalZipFilePath path of the unsecured zip file, which is replaced
	 * @param zipPassword password for the new zip file
	 * @param useZipCrypto true for the weak but widely supported ZipCrypto encryption, false for AES-256
	 * @throws IOException if the zip file does not exist, does not contain exactly one file or cannot be read or written
	 */
	public static void createPasswordSecuredZipFile(final String originalZipFilePath, final char[] zipPassword, final boolean useZipCrypto) throws IOException {
		final File originalZipFile = new File(originalZipFilePath);
		checkZipFileExists(originalZipFile);

		final ZipParameters zipParameters = new ZipParameters();
		zipParameters.setCompressionMethod(CompressionMethod.DEFLATE);
		zipParameters.setEncryptFiles(true);
		if (!useZipCrypto) {
			zipParameters.setEncryptionMethod(EncryptionMethod.AES);
			zipParameters.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
		} else {
			zipParameters.setEncryptionMethod(EncryptionMethod.ZIP_STANDARD);
		}
		String entryFileName = originalZipFile.getName();
		if (entryFileName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
			entryFileName = entryFileName.substring(0, entryFileName.length() - 4);
		}
		zipParameters.setFileNameInZip(entryFileName);

		// Unique temp file in the same directory, so no foreign file is overwritten and the final move stays on one file system
		final File tempFile = File.createTempFile(originalZipFile.getName() + ".", ".tmp", originalZipFile.getAbsoluteFile().getParentFile());
		// zip4j must create the file itself, an existing empty file would be rejected as invalid zip file
		Files.delete(tempFile.toPath());
		try {
			try (ZipFile zipFile = new ZipFile(tempFile, zipPassword)) {
				try (ZipFile unencryptedZipFile = new ZipFile(originalZipFile)) {
					final List<FileHeader> fileHeaders = unencryptedZipFile.getFileHeaders();
					if (fileHeaders == null || fileHeaders.size() != 1 || fileHeaders.get(0).isDirectory()) {
						// Only the first entry is taken over, so any other entries would get lost with the original file
						throw new IOException("Zip file '" + originalZipFilePath + "' must contain exactly one file to be password secured");
					}
					zipParameters.setLastModifiedFileTime(fileHeaders.get(0).getLastModifiedTimeEpoch());
					try (InputStream inputStream = unencryptedZipFile.getInputStream(fileHeaders.get(0))) {
						zipFile.addStream(inputStream, zipParameters);
					}
				}
			}
			Files.move(tempFile.toPath(), originalZipFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
		} catch (final IOException | RuntimeException e) {
			try {
				Files.deleteIfExists(tempFile.toPath());
			} catch (final IOException deleteException) {
				e.addSuppressed(deleteException);
			}
			throw e;
		}
	}

	/**
	 * Returns the total uncompressed size of all entries of a (password secured) zip file.
	 *
	 * @param zipFilePath zip file to read
	 * @param zipPassword password of the zip file, may be null for unsecured zip files
	 * @return sum of the uncompressed sizes in bytes, or -1 if the size of any entry is unknown
	 * @throws IOException if the file does not exist or cannot be read
	 */
	public static long getUncompressedSize(final File zipFilePath, final char[] zipPassword) throws IOException {
		checkZipFileExists(zipFilePath);

		try (ZipFile zipFile = new ZipFile(zipFilePath, zipPassword)) {
			final List<FileHeader> fileHeaders = zipFile.getFileHeaders();
			long uncompressedSize = 0;
			if (fileHeaders != null) {
				for (final FileHeader fileHeader : fileHeaders) {
					final long originalSize = fileHeader.getUncompressedSize();
					if (originalSize >= 0) {
						uncompressedSize += originalSize;
					} else {
						// -1 indicates, that size is unknown
						return originalSize;
					}
				}
			}
			return uncompressedSize;
		}
	}

	/**
	 * Opens the only file of a (password secured) zip file for reading. Directory entries are ignored.
	 *
	 * @param importFilePathOrData path of the zip file
	 * @param zipPassword password of the zip file, may be null for unsecured zip files
	 * @return stream with the content of the file, closing it also closes the zip file
	 * @throws Exception if the zip file cannot be read, contains no file or more than one file
	 */
	public static InputStream openPasswordSecuredZipFile(final String importFilePathOrData, final char[] zipPassword) throws Exception {
		return openPasswordSecuredZipFile(importFilePathOrData, zipPassword, null);
	}

	/**
	 * Opens a file of a (password secured) zip file for reading. Directory entries are ignored.
	 *
	 * @param importFilePathOrData path of the zip file
	 * @param zipPassword password of the zip file, may be null for unsecured zip files
	 * @param zippedFilePathAndName path of the file inside the zip file, or null if the zip file contains only one file
	 * @return stream with the content of the file, closing it also closes the zip file
	 * @throws Exception if the zip file cannot be read or does not contain the file
	 */
	@SuppressWarnings("resource")
	public static InputStream openPasswordSecuredZipFile(final String importFilePathOrData, final char[] zipPassword, final String zippedFilePathAndName) throws Exception {
		checkZipFileExists(new File(importFilePathOrData));

		ZipFile zipFile = null;
		try {
			zipFile = new ZipFile(importFilePathOrData, zipPassword);
			final List<FileHeader> fileHeaders = new ArrayList<>();
			if (zipFile.getFileHeaders() != null) {
				for (final FileHeader fileHeader : zipFile.getFileHeaders()) {
					if (!fileHeader.isDirectory()) {
						fileHeaders.add(fileHeader);
					}
				}
			}

			if (fileHeaders.isEmpty()) {
				throw new Exception("Zip file '" + importFilePathOrData + "' contains no file");
			} else if (Utilities.isBlank(zippedFilePathAndName)) {
				if (fileHeaders.size() == 1) {
					return new InputStreamWithOtherItemsToClose(zipFile.getInputStream(fileHeaders.get(0)), zipFile);
				} else {
					throw new Exception("Zip file '" + importFilePathOrData + "' contains more than one file");
				}
			} else {
				for (final FileHeader fileHeader : fileHeaders) {
					if (fileHeader.getFileName().equals(zippedFilePathAndName)) {
						return new InputStreamWithOtherItemsToClose(zipFile.getInputStream(fileHeader), zipFile);
					}
				}
				throw new Exception("Zip file '" + importFilePathOrData + "' does not include defined zipped file '" + zippedFilePathAndName + "'");
			}
		} catch (final Exception e) {
			try {
				if (zipFile != null) {
					zipFile.close();
				}
			} catch (@SuppressWarnings("unused") final IOException e1) {
				// Do nothing
			}
			throw e;
		}
	}

	/**
	 * zip4j treats a missing file like an empty zip file, so its existence is checked explicitly.
	 */
	private static void checkZipFileExists(final File zipFile) throws IOException {
		if (!zipFile.exists()) {
			throw new IOException("Zip file '" + zipFile.getAbsolutePath() + "' does not exist");
		} else if (!zipFile.isFile()) {
			throw new IOException("Zip file '" + zipFile.getAbsolutePath() + "' is not a file");
		}
	}
}
