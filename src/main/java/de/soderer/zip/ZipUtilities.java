package de.soderer.zip;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Static helper methods for creating, extending, reading and extracting zip files.
 */
public class ZipUtilities {
	private ZipUtilities() {
		// Utility class with static methods only
	}

	/**
	 * Return value of InputStream read methods at the end of the stream.
	 */
	public static final int EOF = -1;

	/**
	 * Charset of zip entry names, which are not marked as UTF-8 (zip specification default, used e.g. by Windows Explorer)
	 */
	private static final Charset LEGACY_ZIP_CHARSET = Charset.forName("Cp437");

	/**
	 * Checks whether a file starts with the signature of a zip file entry ("PK\3\4").
	 * An empty zip file without entries is not detected as zip file.
	 *
	 * @param potentialZipFile file to check
	 * @return true if the file starts like a zip file
	 * @throws FileNotFoundException if the file does not exist
	 * @throws IOException if the file cannot be read
	 */
	public static boolean isZipArchiveFile(final File potentialZipFile) throws FileNotFoundException, IOException {
		try (FileInputStream inputStream = new FileInputStream(potentialZipFile)) {
			final byte[] magicBytes = inputStream.readNBytes(4);
			return magicBytes.length == 4 && magicBytes[0] == 0x50 && magicBytes[1] == 0x4B && magicBytes[2] == 0x03 && magicBytes[3] == 0x04;
		}
	}

	/**
	 * Zip a file or recursively all files and subdirectories of a directory. The
	 * zipped file is placed in the same directory as the zipped file, named like it with ".zip" appended.
	 *
	 * @param sourceFile file or directory to zip
	 * @return the created zip file
	 * @throws IOException if the source does not exist, the zip file exists already or cannot be written
	 */
	public static File zipFile(final File sourceFile) throws IOException {
		final File zippedFile = new File(sourceFile.getAbsolutePath() + ".zip");
		zipFile(sourceFile, zippedFile, null);
		return zippedFile;
	}

	/**
	 * Zip a file or recursively all files and subdirectories of a directory.
	 *
	 * @param sourceFile file or directory to zip
	 * @param destinationZipFile zip file to create, must not exist yet and must not be located inside the source directory
	 * @param fileNameEncodingCharset charset for the entry names, or null for UTF-8
	 * @throws IOException if the source does not exist, the zip file exists already or cannot be written
	 */
	public static void zipFile(final File sourceFile, final File destinationZipFile,
			final Charset fileNameEncodingCharset) throws IOException {
		if (!sourceFile.exists()) {
			throw new IOException("SourceFile does not exist");
		}

		if (destinationZipFile.exists()) {
			throw new IOException("DestinationFile already exists");
		}

		checkZipFileIsNotInsideSource(sourceFile, destinationZipFile);

		try (ZipOutputStream zipOutputStream = openNewZipOutputStream(destinationZipFile, fileNameEncodingCharset)) {
			addFileToOpenZipFileStream(sourceFile, zipOutputStream);
		} catch (final IOException | RuntimeException e) {
			if (destinationZipFile.exists()) {
				destinationZipFile.delete();
			}
			throw e;
		}
	}

	/**
	 * Extracts all entries of a zip file into an existing directory. Entry names are read as Cp437, unless the
	 * entries are marked as UTF-8. Entries pointing outside of the destination directory are rejected.
	 *
	 * @param zipFile zip file to extract
	 * @param destinationDirectory existing directory to extract into
	 * @throws IOException if the zip file cannot be read or an entry cannot be written
	 */
	public static void unzipFile(final File zipFile, final File destinationDirectory) throws IOException {
		unzipFile(zipFile, destinationDirectory, null);
	}

	/**
	 * Extracts a single entry of a zip file into a new file. On error no partial file is left behind.
	 *
	 * @param zipFile zip file to read
	 * @param destinationFilePath file to create, must not exist yet, its directory must exist
	 * @param fileNameEncodingCharset charset of the entry names, or null for Cp437 (entries marked as UTF-8 are always read as UTF-8)
	 * @param filePathInZipFile name of the entry to extract (directory entries are not taken into account)
	 * @return the created file
	 * @throws Exception if the zip file cannot be read, the destination exists already or the entry is not found
	 */
	public static File unzipFile(final File zipFile, final File destinationFilePath, Charset fileNameEncodingCharset,
			final String filePathInZipFile) throws Exception {
		if (!zipFile.exists()) {
			throw new IOException("ZipFile '" + zipFile.getAbsolutePath() + "' does not exist");
		} else if (!zipFile.isFile()) {
			throw new IOException("ZipFile '" + zipFile.getAbsolutePath() + "' is not a file");
		} else if (destinationFilePath.exists()) {
			throw new IOException("Destination file '" + destinationFilePath.getAbsolutePath() + "' already exists");
		}

		if (fileNameEncodingCharset == null) {
			fileNameEncodingCharset = LEGACY_ZIP_CHARSET;
		}

		final byte[] buffer = new byte[8192];
		try (InputStream inputStream = new FileInputStream(zipFile);
				final ZipInputStream zis = new ZipInputStream(inputStream, fileNameEncodingCharset)) {
			ZipEntry zipEntry = zis.getNextEntry();
			while (zipEntry != null) {
				if (!zipEntry.isDirectory() && zipEntry.getName().equals(filePathInZipFile)) {
					final File destFile = destinationFilePath;

					final File parentDirectory = destFile.getAbsoluteFile().getParentFile();
					if (!parentDirectory.exists()) {
						throw new IOException(
								"Destination directory does not exist: " + parentDirectory.getAbsolutePath());
					} else if (!parentDirectory.isDirectory()) {
						throw new IOException("Destination directory exists but is not a directory: "
								+ parentDirectory.getAbsolutePath());
					}

					try (FileOutputStream fos = new FileOutputStream(destFile)) {
						int len;
						while ((len = zis.read(buffer)) != EOF) {
							fos.write(buffer, 0, len);
						}
					} catch (final IOException | RuntimeException e) {
						// Do not leave a truncated file behind
						destFile.delete();
						throw e;
					}

					return destFile;
				}
				zis.closeEntry();
				zipEntry = zis.getNextEntry();
			}
		}
		throw new Exception("File not found in zipfile");
	}

	/**
	 * Extracts all entries of a zip file into an existing directory. Entries pointing outside of the destination
	 * directory are rejected.
	 *
	 * @param zipFile zip file to extract
	 * @param destinationDirectory existing directory to extract into
	 * @param fileNameEncodingCharset charset of the entry names, or null for Cp437 (entries marked as UTF-8 are always read as UTF-8)
	 * @throws IOException if the zip file cannot be read, an entry is outside of the destination or cannot be written
	 */
	public static void unzipFile(final File zipFile, final File destinationDirectory, Charset fileNameEncodingCharset)
			throws IOException {
		if (!zipFile.exists()) {
			throw new IOException("ZipFile '" + zipFile.getAbsolutePath() + "' does not exist");
		} else if (!zipFile.isFile()) {
			throw new IOException("ZipFile '" + zipFile.getAbsolutePath() + "' is not a file");
		} else if (!destinationDirectory.exists()) {
			throw new IOException(
					"Destination directory '" + destinationDirectory.getAbsolutePath() + "' does not exist");
		} else if (!destinationDirectory.isDirectory()) {
			throw new IOException(
					"Destination directory '" + destinationDirectory.getAbsolutePath() + "' is not a directory");
		}

		if (fileNameEncodingCharset == null) {
			fileNameEncodingCharset = LEGACY_ZIP_CHARSET;
		}

		final String destinationDirectoryPath = destinationDirectory.getCanonicalPath();
		final byte[] buffer = new byte[8192];
		try (InputStream inputStream = new FileInputStream(zipFile);
				final ZipInputStream zis = new ZipInputStream(inputStream, fileNameEncodingCharset)) {
			ZipEntry zipEntry = zis.getNextEntry();
			while (zipEntry != null) {
				if (zipEntry.getName().endsWith("/")) {
					final File newDirectory = new File(destinationDirectory, zipEntry.getName());
					// Directory entries like "./" denote the destination directory itself
					if (!isInsideDirectory(destinationDirectoryPath, newDirectory.getCanonicalPath(), true)) {
						throw new IOException(
								"ZipEntry is outside of the destination directory: " + zipEntry.getName());
					}

					if (!newDirectory.exists()) {
						if (!newDirectory.mkdirs()) {
							throw new IOException("Cannot create directory: " + newDirectory.getAbsolutePath());
						}
					} else if (!newDirectory.isDirectory()) {
						throw new IOException("Destination directory exists but is not a directory: "
								+ newDirectory.getAbsolutePath());
					}
				} else {
					final File destFile = new File(destinationDirectory, zipEntry.getName());
					if (!isInsideDirectory(destinationDirectoryPath, destFile.getCanonicalPath(), false)) {
						throw new IOException(
								"ZipEntry is outside of the destination directory: " + zipEntry.getName());
					}

					final File parentDirectory = destFile.getParentFile();
					if (!parentDirectory.exists()) {
						if (!parentDirectory.mkdirs()) {
							throw new IOException("Cannot create directory: " + parentDirectory.getAbsolutePath());
						}
					} else if (!parentDirectory.isDirectory()) {
						throw new IOException("Destination directory exists but is not a directory: "
								+ parentDirectory.getAbsolutePath());
					}

					try (FileOutputStream fos = new FileOutputStream(destFile)) {
						int len;
						while ((len = zis.read(buffer)) != EOF) {
							fos.write(buffer, 0, len);
						}
					}
				}
				zis.closeEntry();
				zipEntry = zis.getNextEntry();
			}
		}
	}

	/**
	 * Returns the destination file for extracting a zip entry, after checking that it is located inside the
	 * destination directory (protection against path traversal, "zip slip").
	 *
	 * @param destinationDir directory to extract into
	 * @param zipEntry entry to extract
	 * @return destination file of the entry
	 * @throws IOException if the entry would be extracted outside of the destination directory
	 */
	public static File newFile(final File destinationDir, final ZipEntry zipEntry) throws IOException {
		final File destFile = new File(destinationDir, zipEntry.getName());

		final String destDirPath = destinationDir.getCanonicalPath();
		final String destFilePath = destFile.getCanonicalPath();

		if (!isInsideDirectory(destDirPath, destFilePath, zipEntry.isDirectory())) {
			throw new IOException("Entry is outside of the target dir: " + zipEntry.getName());
		}

		return destFile;
	}

	/**
	 * Checks whether a canonical path is located inside a canonical directory path. Works also for root
	 * directories like "/" or "C:\", whose canonical path already ends with a separator.
	 *
	 * @param canonicalDirectoryPath canonical path of the directory
	 * @param canonicalPath canonical path to check
	 * @param allowDirectoryItself true if the directory itself is accepted
	 * @return true if the path is inside the directory
	 */
	static boolean isInsideDirectory(final String canonicalDirectoryPath, final String canonicalPath, final boolean allowDirectoryItself) {
		if (canonicalPath.equals(canonicalDirectoryPath)) {
			return allowDirectoryItself;
		}
		final String directoryPrefix = canonicalDirectoryPath.endsWith(File.separator) ? canonicalDirectoryPath : canonicalDirectoryPath + File.separator;
		return canonicalPath.startsWith(directoryPrefix);
	}

	/**
	 * Zip a file or recursively all files and subdirectories of a directory. A new
	 * relative path is started, so the file or directory is located at the top level of the zip file.
	 *
	 * @param sourceFile file or directory to add
	 * @param destinationZipFileSream open zip stream to write to
	 * @throws IOException if the source does not exist or cannot be read
	 */
	public static void addFileToOpenZipFileStream(final File sourceFile, final ZipOutputStream destinationZipFileSream)
			throws IOException {
		addFileToOpenZipFileStream(sourceFile, null, destinationZipFileSream);
	}

	/**
	 * Zip a file or recursively all files and subdirectories of a directory. Empty directories are stored as
	 * directory entries. Directory cycles caused by symbolic links are rejected.
	 *
	 * @param sourceFile file or directory to add
	 * @param relativeDirPath directory inside the zip file to add to, null or empty for the top level
	 * @param destinationZipFileStream open zip stream to write to
	 * @throws IOException if the source does not exist or cannot be read
	 */
	public static void addFileToOpenZipFileStream(final File sourceFile, String relativeDirPath,
			final ZipOutputStream destinationZipFileStream) throws IOException {
		if (!sourceFile.exists()) {
			throw new IOException("SourceFile does not exist");
		}

		if (destinationZipFileStream == null) {
			throw new IOException("DestinationStream is not ready");
		}

		if (relativeDirPath == null) {
			relativeDirPath = "";
		}

		// Zip entry names always use "/" as separator and must not start with a separator
		relativeDirPath = relativeDirPath.replace('\\', '/');
		while (relativeDirPath.startsWith("/")) {
			relativeDirPath = relativeDirPath.substring(1);
		}
		if (relativeDirPath.length() > 0 && !relativeDirPath.endsWith("/")) {
			relativeDirPath = relativeDirPath + "/";
		}

		addFileToOpenZipFileStream(sourceFile, relativeDirPath, destinationZipFileStream, new HashSet<>());
	}

	/**
	 * Recursive part of addFileToOpenZipFileStream.
	 *
	 * @param relativeDirPath normalized directory path inside the zip file, empty or ending with "/"
	 * @param ancestorDirectories canonical paths of the directories currently being zipped, to detect cycles
	 */
	private static void addFileToOpenZipFileStream(final File sourceFile, final String relativeDirPath,
			final ZipOutputStream destinationZipFileStream, final Set<String> ancestorDirectories) throws IOException {
		if (!sourceFile.isDirectory()) {
			final ZipEntry entry = new ZipEntry(relativeDirPath + sourceFile.getName());
			entry.setTime(sourceFile.lastModified());
			destinationZipFileStream.putNextEntry(entry);

			try (BufferedInputStream bufferedFileInputStream = new BufferedInputStream(
					new FileInputStream(sourceFile))) {
				final byte[] bufferArray = new byte[8192];
				int byteBufferFillLength;
				while ((byteBufferFillLength = bufferedFileInputStream.read(bufferArray)) != EOF) {
					destinationZipFileStream.write(bufferArray, 0, byteBufferFillLength);
				}
			}
			destinationZipFileStream.closeEntry();
		} else {
			final String canonicalDirectoryPath = sourceFile.getCanonicalPath();
			if (!ancestorDirectories.add(canonicalDirectoryPath)) {
				throw new IOException("Directory cycle detected (symbolic link): " + sourceFile.getAbsolutePath());
			}

			final File[] sourceSubFiles = sourceFile.listFiles();
			if (sourceSubFiles == null) {
				throw new IOException("Cannot read directory: " + sourceFile.getAbsolutePath());
			}

			final String directoryEntryPath = relativeDirPath + sourceFile.getName() + "/";
			if (sourceSubFiles.length == 0) {
				// Store empty directories explicitly, otherwise they would get lost
				final ZipEntry directoryEntry = new ZipEntry(directoryEntryPath);
				directoryEntry.setTime(sourceFile.lastModified());
				destinationZipFileStream.putNextEntry(directoryEntry);
				destinationZipFileStream.closeEntry();
			} else {
				for (final File sourceSubFile : sourceSubFiles) {
					addFileToOpenZipFileStream(sourceSubFile, directoryEntryPath, destinationZipFileStream, ancestorDirectories);
				}
			}

			ancestorDirectories.remove(canonicalDirectoryPath);
		}
	}

	/**
	 * Add file data as a new entry at the top level of an opened ZipOutputStream
	 *
	 * @param fileData content of the new entry
	 * @param filename name of the new entry
	 * @param destinationZipFileSream open zip stream to write to
	 * @throws IOException if a parameter is missing or the stream cannot be written
	 */
	public static void addFileDataToOpenZipFileStream(final byte[] fileData, final String filename,
			final ZipOutputStream destinationZipFileSream) throws IOException {
		addFileDataToOpenZipFileStream(fileData, "", filename, destinationZipFileSream);
	}

	/**
	 * Open a ZipOutputStream based on a new file in which is written
	 *
	 * @param destinationZipFile zip file to create, must not exist yet, its directory must exist
	 * @param fileNameEncodingCharset charset for the entry names, or null for UTF-8
	 * @return open zip stream, the caller must close it
	 * @throws IOException if the file exists already, its directory does not exist or it cannot be created
	 */
	public static ZipOutputStream openNewZipOutputStream(final File destinationZipFile, final Charset fileNameEncodingCharset) throws IOException {
		if (destinationZipFile.exists()) {
			throw new IOException("DestinationFile already exists");
		} else if (!destinationZipFile.getAbsoluteFile().getParentFile().exists()) {
			throw new IOException("DestinationDirectory does not exist");
		}

		try {
			if (fileNameEncodingCharset == null) {
				// UTF-8 file names, marked as such in the zip entries (ZipOutputStream default)
				return new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(destinationZipFile)));
			} else {
				return new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(destinationZipFile)), fileNameEncodingCharset);
			}
		} catch (final IOException e) {
			if (destinationZipFile.exists()) {
				destinationZipFile.delete();
			}
			throw e;
		}
	}

	/**
	 * Open a ZipOutputStream based on a OutputStream in which is written
	 *
	 * @param destinationZipStream stream to write the zip data to
	 * @return open zip stream, the caller must close it
	 * @throws IOException if the stream is missing
	 */
	public static ZipOutputStream openNewZipOutputStream(final OutputStream destinationZipStream) throws IOException {
		if (destinationZipStream == null) {
			throw new IOException("DestinationStream is missing");
		}

		return new ZipOutputStream(new BufferedOutputStream(destinationZipStream));
	}

	/**
	 * Add file data as a new entry to an opened ZipOutputStream
	 *
	 * @param fileData content of the new entry
	 * @param relativeDirPath directory inside the zip file, empty for the top level, otherwise ending with "/" (or "\")
	 * @param filename name of the new entry
	 * @param destinationZipFileStream open zip stream to write to
	 * @throws IOException if a parameter is missing or invalid or the stream cannot be written
	 */
	public static void addFileDataToOpenZipFileStream(final byte[] fileData, final String relativeDirPath,
			final String filename, final ZipOutputStream destinationZipFileStream) throws IOException {
		if (fileData == null) {
			throw new IOException("FileData is missing");
		}

		if (Utilities.isBlank(filename)) {
			throw new IOException("Filename is missing");
		}

		if (destinationZipFileStream == null) {
			throw new IOException("DestinationStream is not ready");
		}

		if (relativeDirPath == null || (relativeDirPath.length() > 0 && !relativeDirPath.endsWith("/") && !relativeDirPath.endsWith("\\"))) {
			throw new IOException("RelativeDirPath is invalid");
		}

		// Zip entry names always use "/" as separator and must not start with a separator
		String entryName = (relativeDirPath + filename).replace('\\', '/');
		while (entryName.startsWith("/")) {
			entryName = entryName.substring(1);
		}
		final ZipEntry entry = new ZipEntry(entryName);
		entry.setTime(System.currentTimeMillis());
		destinationZipFileStream.putNextEntry(entry);

		destinationZipFileStream.write(fileData);

		destinationZipFileStream.closeEntry();
	}

	/**
	 * Add a file or recursively all files and subdirectories of a directory to an
	 * existing ZipFile. All existing entries are preserved. If adding fails, the zip file is left unchanged.
	 *
	 * @param sourceFile file or directory to add
	 * @param zipFile existing zip file to extend
	 * @throws IOException if a file cannot be read, an entry exists already or the zip file cannot be written
	 */
	public static void addFileToExistingZipFile(final File sourceFile, final File zipFile) throws IOException {
		addFileToExistingZipFile(Collections.singletonList(sourceFile), zipFile);
	}

	/**
	 * Add a list of files or recursively all files and subdirectories of a
	 * directory to an existing ZipFile. All existing entries are preserved. If adding fails, the zip file is left
	 * unchanged.
	 *
	 * @param sourceFiles files or directories to add
	 * @param zipFile existing zip file to extend
	 * @throws IOException if a file cannot be read, an entry exists already or the zip file cannot be written
	 */
	public static void addFileToExistingZipFile(final List<File> sourceFiles, final File zipFile) throws IOException {
		if (!zipFile.exists()) {
			throw new IOException("ZipFile '" + zipFile.getAbsolutePath() + "' does not exist");
		}
		for (final File sourceFile : sourceFiles) {
			if (!sourceFile.exists()) {
				throw new IOException("SourceFile '" + sourceFile.getAbsolutePath() + "' does not exist");
			}
			checkZipFileIsNotInsideSource(sourceFile, zipFile);
		}

		// The new content is written into a temp file, which replaces the original only after complete success
		final File tempZipFile = File.createTempFile(zipFile.getName() + ".", ".tmp", zipFile.getAbsoluteFile().getParentFile());
		try {
			try (ZipFile sourceZipFile = openZipFileForReading(zipFile);
					ZipOutputStream zipOutputStream = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tempZipFile)))) {
				copyZipEntries(sourceZipFile, zipOutputStream, null);
				for (final File sourceFile : sourceFiles) {
					addFileToOpenZipFileStream(sourceFile, zipOutputStream);
				}
			}
			Files.move(tempZipFile.toPath(), zipFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
		} catch (final IOException | RuntimeException e) {
			try {
				Files.deleteIfExists(tempZipFile.toPath());
			} catch (final IOException deleteException) {
				e.addSuppressed(deleteException);
			}
			throw e;
		}
	}

	/**
	 * Open an existing ZipFile for appending new files or create a new ZipFile if
	 * it does not exist yet.
	 *
	 * @param zipFile zip file to extend or create
	 * @return open zip stream containing all existing entries, the caller must close it
	 * @throws IOException if the zip file cannot be read or written
	 */
	public static ZipOutputStream openExistingZipFileForExtensionOrCreateNewZipFile(final File zipFile) throws IOException {
		if (zipFile.exists()) {
			return openExistingZipFileForExtension(zipFile);
		} else {
			return openNewZipOutputStream(zipFile, null);
		}
	}

	/**
	 * Open an existing ZipFile for appending new files. All existing entries are
	 * preserved by copying them into a new zip file with the same name.<br>
	 * If opening fails, the original zip file is restored. Errors while appending to the returned stream cannot be
	 * rolled back anymore; use addFileToExistingZipFile for an all-or-nothing extension.
	 *
	 * @param zipFile existing zip file to extend
	 * @return open zip stream containing all existing entries, the caller must close it
	 * @throws IOException if the zip file does not exist or cannot be read or written
	 */
	public static ZipOutputStream openExistingZipFileForExtension(final File zipFile) throws IOException {
		if (!zipFile.exists()) {
			throw new IOException("ZipFile '" + zipFile.getAbsolutePath() + "' does not exist");
		}
		final File originalFileTemp = File.createTempFile(zipFile.getName() + ".", ".tmp", zipFile.getAbsoluteFile().getParentFile());
		Files.move(zipFile.toPath(), originalFileTemp.toPath(), StandardCopyOption.REPLACE_EXISTING);

		ZipOutputStream zipOutputStream = null;
		try (ZipFile sourceZipFile = openZipFileForReading(originalFileTemp)) {
			zipOutputStream = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)));
			copyZipEntries(sourceZipFile, zipOutputStream, null);
			zipOutputStream.flush();
		} catch (final IOException | RuntimeException e) {
			// sourceZipFile is already closed at this point, which is required on Windows for moving the file back
			Utilities.closeQuietly(zipOutputStream);
			try {
				Files.move(originalFileTemp.toPath(), zipFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			} catch (final IOException moveException) {
				e.addSuppressed(moveException);
			}
			throw e;
		}

		originalFileTemp.delete();
		return zipOutputStream;
	}

	/**
	 * Read all entries of a ZipFile into memory
	 *
	 * @param zipFile zip file to read
	 * @return All entries in a map (in the order of the zip file), with the entry names as keys and their content as values
	 * @throws IOException if the zip file cannot be read
	 */
	public static Map<String, byte[]> readExistingZipFile(final File zipFile) throws IOException {
		final Map<String, byte[]> returnMap = new LinkedHashMap<>();
		try (ZipFile sourceZipFile = openZipFileForReading(zipFile)) {
			final Enumeration<? extends ZipEntry> srcEntries = sourceZipFile.entries();
			while (srcEntries.hasMoreElements()) {
				final ZipEntry sourceZipFileEntry = srcEntries.nextElement();

				try (InputStream inputStream = sourceZipFile.getInputStream(sourceZipFileEntry)) {
					final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
					inputStream.transferTo(byteArrayOutputStream);
					returnMap.put(sourceZipFileEntry.getName(), byteArrayOutputStream.toByteArray());
				}
			}
		}
		return returnMap;
	}

	/**
	 * Returns the names of all entries of a zip file.
	 *
	 * @param file zip file to read
	 * @return names of all entries, including directories
	 * @throws ZipException if the file is no valid zip file
	 * @throws IOException if the file cannot be read
	 */
	public static List<String> getZipFileEntries(final File file) throws ZipException, IOException {
		try (ZipFile zipFile = openZipFileForReading(file)) {
			final Enumeration<? extends ZipEntry> entries = zipFile.entries();
			final List<String> entryList = new ArrayList<>();
			while (entries.hasMoreElements()) {
				final ZipEntry entry = entries.nextElement();
				entryList.add(entry.getName());
			}
			return entryList;
		}
	}

	/**
	 * Removes all entries matching any of the given patterns from a zip file.
	 *
	 * @param zipFile zip file to change
	 * @param fileNameEncodingCharset charset for the entry names of the rewritten zip file, or null for UTF-8
	 * @param filePatterns patterns of the entry names to remove, with "*" for any characters and "?" for one character
	 * @throws Exception if the zip file cannot be read or written
	 */
	public static void removeFilesFromZipFile(final File zipFile, final Charset fileNameEncodingCharset,
			final String... filePatterns) throws Exception {
		if (!zipFile.exists()) {
			throw new IOException("ZipFile '" + zipFile.getAbsolutePath() + "' does not exist");
		}

		// Temp file in the same directory, so it can replace the original file by a simple move
		final File tempZipFile = File.createTempFile(zipFile.getName() + ".", ".tmp", zipFile.getAbsoluteFile().getParentFile());
		try {
			// createTempFile already created the file, but the target of removeFilesFromZipFile must not exist yet
			Files.delete(tempZipFile.toPath());
			removeFilesFromZipFile(zipFile, tempZipFile, fileNameEncodingCharset, filePatterns);
			Files.move(tempZipFile.toPath(), zipFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
		} catch (final Exception e) {
			if (tempZipFile.exists()) {
				tempZipFile.delete();
			}
			throw e;
		}
	}

	/**
	 * Copies a zip file into a new zip file, leaving out all entries matching any of the given patterns.
	 * On error the new zip file is deleted again.
	 *
	 * @param originalZipFile zip file to read
	 * @param shrinkedZipFile zip file to create, must not exist yet
	 * @param fileNameEncodingCharset charset for the entry names of the new zip file, or null for UTF-8
	 * @param filePatterns patterns of the entry names to leave out, with "*" for any characters and "?" for one character
	 * @throws Exception if the zip files cannot be read or written
	 */
	public static void removeFilesFromZipFile(final File originalZipFile, final File shrinkedZipFile,
			final Charset fileNameEncodingCharset, final String... filePatterns) throws Exception {
		if (filePatterns == null) {
			throw new IllegalArgumentException("FilePatterns are missing");
		} else if (shrinkedZipFile.exists()) {
			throw new IOException("DestinationFile already exists");
		}

		final List<Pattern> patternsToFilter = new ArrayList<>();
		for (final String filePattern : filePatterns) {
			if (filePattern == null) {
				throw new IllegalArgumentException("FilePattern is missing");
			}
			patternsToFilter.add(Pattern.compile(wildcardPatternToRegex(filePattern.replace("\\", "/"))));
		}

		try (ZipFile sourceZipFile = openZipFileForReading(originalZipFile);
				ZipOutputStream destinationZipOutputStream = openNewZipOutputStream(shrinkedZipFile,
						fileNameEncodingCharset)) {
			copyZipEntries(sourceZipFile, destinationZipOutputStream, patternsToFilter);
		} catch (final IOException | RuntimeException e) {
			if (shrinkedZipFile.exists()) {
				shrinkedZipFile.delete();
			}
			throw e;
		}
	}

	/**
	 * Copies all entries of a zip file into an open zip stream.
	 *
	 * @param sourceZipFile zip file to read
	 * @param destinationZipOutputStream open zip stream to write to
	 * @param patternsToFilter entries matching any of these patterns are left out, may be null
	 */
	private static void copyZipEntries(final ZipFile sourceZipFile, final ZipOutputStream destinationZipOutputStream,
			final List<Pattern> patternsToFilter) throws IOException {
		final Enumeration<? extends ZipEntry> srcEntries = sourceZipFile.entries();
		while (srcEntries.hasMoreElements()) {
			final ZipEntry sourceZipFileEntry = srcEntries.nextElement();
			boolean keepFileEntry = true;
			if (patternsToFilter != null) {
				for (final Pattern pattern : patternsToFilter) {
					if (pattern.matcher(sourceZipFileEntry.getName()).matches()) {
						keepFileEntry = false;
						break;
					}
				}
			}
			if (keepFileEntry) {
				destinationZipOutputStream.putNextEntry(createRecompressedEntryCopy(sourceZipFileEntry));
				try (InputStream inputStream = sourceZipFile.getInputStream(sourceZipFileEntry)) {
					inputStream.transferTo(destinationZipOutputStream);
				}
				destinationZipOutputStream.closeEntry();
			}
		}
	}

	/**
	 * Opens a zip file for reading. Entry names are read as UTF-8. If the zip file contains entry names, which are
	 * not valid UTF-8 and not marked as UTF-8 (e.g. created by Windows Explorer), they are read as Cp437 like in
	 * unzipFile.
	 *
	 * @param file zip file to open
	 * @return open zip file, the caller must close it
	 * @throws IOException if the file is no valid zip file or cannot be read
	 */
	private static ZipFile openZipFileForReading(final File file) throws IOException {
		ZipException utf8Exception;
		try {
			final ZipFile zipFile = new ZipFile(file, StandardCharsets.UTF_8);
			try {
				// Older JDKs report invalid entry names only on access, so all names are decoded once
				final Enumeration<? extends ZipEntry> entries = zipFile.entries();
				while (entries.hasMoreElements()) {
					entries.nextElement().getName();
				}
				return zipFile;
			} catch (final IllegalArgumentException e) {
				zipFile.close();
				utf8Exception = new ZipException("Invalid UTF-8 entry name: " + e.getMessage());
			}
		} catch (final ZipException e) {
			utf8Exception = e;
		}

		try {
			return new ZipFile(file, LEGACY_ZIP_CHARSET);
		} catch (final IOException e) {
			e.addSuppressed(utf8Exception);
			throw e;
		}
	}

	/**
	 * Ensures that a zip file is not located inside a directory to be zipped, which would make the zip file
	 * contain itself.
	 */
	private static void checkZipFileIsNotInsideSource(final File sourceFile, final File zipFile) throws IOException {
		if (sourceFile.isDirectory() && isInsideDirectory(sourceFile.getCanonicalPath(), zipFile.getCanonicalPath(), false)) {
			throw new IOException("Zip file '" + zipFile.getAbsolutePath() + "' must not be located inside the source directory '" + sourceFile.getAbsolutePath() + "'");
		}
	}

	/**
	 * Copy of a zip entry for writing it into another zip file. The compressed size is reset, because the data is
	 * compressed again and the result may differ, which ZipOutputStream would reject as "invalid entry compressed size".
	 */
	private static ZipEntry createRecompressedEntryCopy(final ZipEntry sourceZipFileEntry) {
		final ZipEntry newEntry = new ZipEntry(sourceZipFileEntry);
		newEntry.setCompressedSize(-1);
		return newEntry;
	}

	/**
	 * Converts a file name pattern with the wildcards "*" (any characters) and "?" (one character) into a regular
	 * expression. All other characters are matched literally.
	 */
	private static String wildcardPatternToRegex(final String wildcardPattern) {
		final StringBuilder regex = new StringBuilder("^");
		final StringBuilder literal = new StringBuilder();
		for (final char character : wildcardPattern.toCharArray()) {
			if (character == '*' || character == '?') {
				if (literal.length() > 0) {
					regex.append(Pattern.quote(literal.toString()));
					literal.setLength(0);
				}
				regex.append(character == '*' ? ".*" : ".");
			} else {
				literal.append(character);
			}
		}
		if (literal.length() > 0) {
			regex.append(Pattern.quote(literal.toString()));
		}
		return regex.append("$").toString();
	}

	/**
	 * Returns the total uncompressed size of all entries of a zip file.
	 *
	 * @param zippedFile zip file to read
	 * @return sum of the uncompressed sizes in bytes, or -1 if the size of any entry is unknown
	 * @throws ZipException if the file is no valid zip file
	 * @throws IOException if the file cannot be read
	 */
	public static long getDataSizeUncompressed(final File zippedFile) throws ZipException, IOException {
		try (ZipFile zipFile = openZipFileForReading(zippedFile)) {
			long uncompressedSize = 0;
			final Enumeration<? extends ZipEntry> e = zipFile.entries();
			while (e.hasMoreElements()) {
				final ZipEntry entry = e.nextElement();
				final long originalSize = entry.getSize();
				if (originalSize >= 0) {
					uncompressedSize += originalSize;
				} else {
					// -1 indicates, that size is unknown
					return originalSize;
				}
			}
			return uncompressedSize;
		}
	}

	/**
	 * Opens the only file of a zip file for reading. Directory entries are ignored.
	 *
	 * @param importFilePathOrData path of the zip file
	 * @return stream with the content of the file, closing it also closes the zip file
	 * @throws Exception if the zip file cannot be read, contains no file or more than one file
	 */
	public static InputStream openZipFile(final String importFilePathOrData) throws Exception {
		return openZipFile(importFilePathOrData, null);
	}

	/**
	 * Opens a file of a zip file for reading. Directory entries are ignored.
	 *
	 * @param importFilePathOrData path of the zip file
	 * @param zippedFilePathAndName path of the file inside the zip file, or null if the zip file contains only one file
	 * @return stream with the content of the file, closing it also closes the zip file
	 * @throws Exception if the zip file cannot be read or does not contain the file
	 */
	@SuppressWarnings("resource")
	public static InputStream openZipFile(final String importFilePathOrData, final String zippedFilePathAndName) throws Exception {
		final File file = new File(importFilePathOrData);
		if (!file.exists()) {
			throw new IOException("Zip file '" + importFilePathOrData + "' does not exist");
		} else if (!file.isFile()) {
			throw new IOException("Zip file '" + importFilePathOrData + "' is not a file");
		}

		ZipFile zipFile = null;
		try {
			zipFile = openZipFileForReading(file);
			final List<ZipEntry> fileEntries = new ArrayList<>();
			for (final ZipEntry entry : Collections.list(zipFile.entries())) {
				if (!entry.isDirectory()) {
					fileEntries.add(entry);
				}
			}

			if (fileEntries.isEmpty()) {
				throw new Exception("Zip file '" + importFilePathOrData + "' contains no file");
			} else if (Utilities.isBlank(zippedFilePathAndName)) {
				if (fileEntries.size() == 1) {
					return new InputStreamWithOtherItemsToClose(zipFile.getInputStream(fileEntries.get(0)), zipFile);
				} else {
					throw new Exception("Zip file '" + importFilePathOrData + "' contains more than one file");
				}
			} else {
				for (final ZipEntry fileEntry : fileEntries) {
					if (fileEntry.getName().equals(zippedFilePathAndName)) {
						return new InputStreamWithOtherItemsToClose(zipFile.getInputStream(fileEntry), zipFile);
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
}
