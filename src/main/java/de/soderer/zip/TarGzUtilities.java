package de.soderer.zip;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

/**
 * Static helper methods for reading and writing gzip compressed tar archives (".tar.gz").
 */
public class TarGzUtilities {
	private TarGzUtilities() {
		// Utility class with static methods only
	}

	/**
	 * Returns the total size of all files in a tar.gz archive.
	 *
	 * @param tarGzFile tar.gz archive to read
	 * @return sum of the uncompressed sizes of all files in bytes
	 * @throws Exception if the archive does not exist or cannot be read
	 */
	public static long getUncompressedSize(final File tarGzFile) throws Exception {
		checkTarGzFileExists(tarGzFile);
		try (TarArchiveInputStream tarArchiveInputStream = openTarArchiveInputStream(tarGzFile)) {
			TarArchiveEntry entry;
			long size = 0;
			while ((entry = getNextEntry(tarArchiveInputStream)) != null) {
				if (isRegularFile(entry)) {
					size += entry.getRealSize();
				}
			}
			return size;
		} catch (final Exception e) {
			throw new Exception("Cannot read '" + tarGzFile + "'", e);
		}
	}

	/**
	 * Returns the number of files (without directories, links and special files) in a tar.gz archive.
	 *
	 * @param tarGzFile tar.gz archive to read
	 * @return number of files
	 * @throws Exception if the archive does not exist or cannot be read
	 */
	public static int getFilesCount(final File tarGzFile) throws Exception {
		checkTarGzFileExists(tarGzFile);
		try (TarArchiveInputStream tarArchiveInputStream = openTarArchiveInputStream(tarGzFile)) {
			TarArchiveEntry entry;
			int count = 0;
			while ((entry = getNextEntry(tarArchiveInputStream)) != null) {
				if (isRegularFile(entry)) {
					count++;
				}
			}
			return count;
		} catch (final Exception e) {
			throw new Exception("Cannot read '" + tarGzFile + "'", e);
		}
	}

	/**
	 * Extracts all entries of a tar.gz archive into a new directory. Entries pointing outside of the destination
	 * directory (path traversal) are rejected. On error the destination directory is deleted again.<br>
	 * <ul>
	 * <li>Directories are created, including empty ones</li>
	 * <li>Unix file permissions are applied on file systems supporting them</li>
	 * <li>Hard links are extracted as copies of their target file</li>
	 * <li>Symbolic links are created after all other entries and must resolve to an existing path inside the
	 * destination directory. Where symbolic links cannot be created (e.g. Windows without the required privilege),
	 * links to files are extracted as copies of their target and links to directories are skipped.</li>
	 * <li>Device files and FIFOs are skipped</li>
	 * </ul>
	 *
	 * @param tarGzFile tar.gz archive to extract
	 * @param decompressToPath destination directory, which must not exist yet
	 * @throws Exception if the archive does not exist or cannot be extracted, or the destination already exists
	 */
	public static void decompress(final File tarGzFile, final File decompressToPath) throws Exception {
		checkTarGzFileExists(tarGzFile);
		if (decompressToPath.exists()) {
			throw new Exception("Destination path already exists: " + decompressToPath.getAbsolutePath());
		}

		final List<TarArchiveEntry> symbolicLinkEntries = new ArrayList<>();
		try (TarArchiveInputStream tarArchiveInputStream = openTarArchiveInputStream(tarGzFile)) {
			if (!decompressToPath.mkdirs()) {
				throw new IOException("Cannot create destination directory: " + decompressToPath.getAbsolutePath());
			}
			final String destinationPath = decompressToPath.getCanonicalPath();

			TarArchiveEntry entry;
			while ((entry = getNextEntry(tarArchiveInputStream)) != null) {
				final File currentFile = getCheckedDestinationFile(tarGzFile, decompressToPath, destinationPath, entry.getName(), entry.isDirectory());

				if (entry.isDirectory()) {
					if (!currentFile.isDirectory() && !currentFile.mkdirs()) {
						throw new IOException("Cannot create directory: " + currentFile.getAbsolutePath());
					}
				} else if (entry.isSymbolicLink()) {
					// Created at the end, so no file of the archive can be written through a symbolic link
					symbolicLinkEntries.add(entry);
				} else if (entry.isLink()) {
					// Hard link: the link name is the path of an earlier entry, relative to the archive root
					final File linkTarget = getCheckedDestinationFile(tarGzFile, decompressToPath, destinationPath, entry.getLinkName(), false);
					if (!linkTarget.isFile()) {
						throw new IOException("Target of hard link '" + entry.getName() + "' not found: " + entry.getLinkName());
					}
					createParentDirectory(currentFile);
					Files.copy(linkTarget.toPath(), currentFile.toPath());
				} else if (entry.isCharacterDevice() || entry.isBlockDevice() || entry.isFIFO()) {
					// Special files cannot be extracted as normal files
					continue;
				} else {
					createParentDirectory(currentFile);
					try (FileOutputStream fileOutputStream = new FileOutputStream(currentFile)) {
						tarArchiveInputStream.transferTo(fileOutputStream);
					}
					applyFilePermissions(currentFile, entry.getMode());
				}
			}

			createSymbolicLinks(tarGzFile, decompressToPath, destinationPath, symbolicLinkEntries);
		} catch (final Exception e) {
			try {
				if (decompressToPath.exists()) {
					deleteRecursivelyWithoutFollowingLinks(decompressToPath.toPath());
				}
			} catch (final Exception e1) {
				e.addSuppressed(e1);
			}

			throw new Exception("Cannot decompress '" + tarGzFile + "'", e);
		}
	}

	/**
	 * Creates the symbolic links of an archive. Each link must resolve to an existing path inside the destination
	 * directory, otherwise all created links are removed again and an exception is thrown.
	 */
	private static void createSymbolicLinks(final File tarGzFile, final File decompressToPath, final String destinationPath, final List<TarArchiveEntry> symbolicLinkEntries) throws Exception {
		final Path destinationDirectory = Paths.get(destinationPath);
		final List<Path> createdLinks = new ArrayList<>();
		final List<TarArchiveEntry> notCreatedLinks = new ArrayList<>();
		try {
			for (final TarArchiveEntry linkEntry : symbolicLinkEntries) {
				final File linkFile = getCheckedDestinationFile(tarGzFile, decompressToPath, destinationPath, linkEntry.getName(), false);
				final Path linkTargetPath = Paths.get(linkEntry.getLinkName().replace("\\", "/"));
				// Quick lexical check, the reliable check of the real path follows after all links are created
				if (linkTargetPath.isAbsolute() || !linkFile.getCanonicalFile().getParentFile().toPath().resolve(linkTargetPath).normalize().startsWith(destinationDirectory)) {
					throw new Exception("Traversal error in tar gz file: " + tarGzFile.getAbsolutePath() + " (symbolic link '" + linkEntry.getName() + "')");
				}
				createParentDirectory(linkFile);
				try {
					createdLinks.add(Files.createSymbolicLink(linkFile.toPath(), linkTargetPath));
				} catch (final UnsupportedOperationException | FileSystemException e) {
					if (Files.exists(linkFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
						// The path is used by another entry, which is no platform restriction
						throw e;
					}
					notCreatedLinks.add(linkEntry);
				}
			}

			// Links may point to other links, so the real paths can only be checked after all links exist
			for (final Path createdLink : createdLinks) {
				final Path realPath;
				try {
					realPath = createdLink.toRealPath();
				} catch (final IOException e) {
					throw new Exception("Symbolic link does not resolve to an existing path: " + createdLink, e);
				}
				if (!realPath.startsWith(destinationDirectory)) {
					throw new Exception("Traversal error in tar gz file: " + tarGzFile.getAbsolutePath() + " (symbolic link '" + createdLink + "')");
				}
			}
		} catch (final Exception e) {
			// Remove the links before the destination is deleted, so the deletion can never reach outside
			for (final Path createdLink : createdLinks) {
				try {
					Files.deleteIfExists(createdLink);
				} catch (final IOException e1) {
					e.addSuppressed(e1);
				}
			}
			throw e;
		}

		// Fallback for platforms without symbolic links: copy linked files, skip linked directories
		for (final TarArchiveEntry linkEntry : notCreatedLinks) {
			final File linkFile = getCheckedDestinationFile(tarGzFile, decompressToPath, destinationPath, linkEntry.getName(), false);
			final File linkTarget = new File(linkFile.getParentFile(), linkEntry.getLinkName().replace("\\", "/"));
			if (ZipUtilities.isInsideDirectory(destinationPath, linkTarget.getCanonicalPath(), false) && linkTarget.isFile()) {
				Files.copy(linkTarget.toPath(), linkFile.toPath());
			}
		}
	}

	/**
	 * Returns the destination file of an archive entry, after checking that it is located inside the destination
	 * directory.
	 */
	private static File getCheckedDestinationFile(final File tarGzFile, final File decompressToPath, final String destinationPath, final String entryName, final boolean allowDestinationItself) throws Exception {
		final String entryFilePath = entryName.replace("\\", "/");
		if (entryFilePath.startsWith("/") || entryFilePath.equals("..") || entryFilePath.startsWith("../") || entryFilePath.endsWith("/..") || entryFilePath.contains("/../")) {
			throw new Exception("Traversal error in tar gz file: " + tarGzFile.getAbsolutePath() + " (entry '" + entryName + "')");
		}
		final File destinationFile = new File(decompressToPath, entryFilePath);
		if (!ZipUtilities.isInsideDirectory(destinationPath, destinationFile.getCanonicalPath(), allowDestinationItself)) {
			throw new Exception("Traversal error in tar gz file: " + tarGzFile.getAbsolutePath() + " (entry '" + entryName + "')");
		}
		return destinationFile;
	}

	private static void createParentDirectory(final File file) throws IOException {
		final File parent = file.getParentFile();
		if (!parent.isDirectory() && !parent.mkdirs()) {
			throw new IOException("Cannot create directory: " + parent.getAbsolutePath());
		}
	}

	/**
	 * Applies the Unix permission bits of a tar entry, if the file system supports them. Special bits (setuid,
	 * setgid, sticky) are ignored.
	 */
	private static void applyFilePermissions(final File file, final int mode) throws IOException {
		if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
			return;
		}
		final Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
		final PosixFilePermission[] permissionsByBit = {
				PosixFilePermission.OTHERS_EXECUTE, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_READ,
				PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_READ,
				PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_READ };
		for (int bit = 0; bit < permissionsByBit.length; bit++) {
			if ((mode & (1 << bit)) != 0) {
				permissions.add(permissionsByBit[bit]);
			}
		}
		// Keep the extracted file readable and writable for its owner, even if the archive says otherwise
		permissions.add(PosixFilePermission.OWNER_READ);
		permissions.add(PosixFilePermission.OWNER_WRITE);
		Files.setPosixFilePermissions(file.toPath(), permissions);
	}

	/**
	 * Deletes a directory tree. Symbolic links are deleted themselves, their targets are never touched.
	 */
	private static void deleteRecursivelyWithoutFollowingLinks(final Path path) throws IOException {
		Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(final Path directory, final IOException exception) throws IOException {
				if (exception != null) {
					throw exception;
				}
				Files.delete(directory);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/**
	 * Creates a tar.gz archive containing a single file, stored under its file name.
	 *
	 * @param tarGzFile tar.gz archive to create, must not exist yet
	 * @param fileToCompress file to put into the archive
	 * @throws IOException if the archive exists already or cannot be written
	 */
	public static void compress(final File tarGzFile, final File fileToCompress) throws IOException {
		compress(tarGzFile, fileToCompress, null);
	}

	/**
	 * Creates a tar.gz archive containing a single file. Long paths and files larger than 8 GiB are stored in POSIX
	 * (pax) format.
	 *
	 * @param tarGzFile tar.gz archive to create, must not exist yet
	 * @param fileToCompress file to put into the archive
	 * @param filePathInTarGzFile path of the file inside the archive, or null to use the file name
	 * @throws IOException if the archive exists already, the file to compress is missing or the archive cannot be written
	 */
	public static void compress(final File tarGzFile, final File fileToCompress, String filePathInTarGzFile) throws IOException {
		if (tarGzFile.exists()) {
			throw new IOException("TarGz file already exists: " + tarGzFile.getAbsolutePath());
		} else if (!tarGzFile.getAbsoluteFile().getParentFile().exists()) {
			throw new IOException("Parent directory for TarGz file does not exist: " + tarGzFile.getAbsoluteFile().getParentFile().getAbsolutePath());
		} else if (!fileToCompress.exists()) {
			throw new IOException("File to compress does not exist: " + fileToCompress.getAbsolutePath());
		} else if (!fileToCompress.isFile()) {
			throw new IOException("File to compress is not a file: " + fileToCompress.getAbsolutePath());
		}

		if (filePathInTarGzFile == null) {
			filePathInTarGzFile = fileToCompress.getName();
		}

		try (TarArchiveOutputStream tarArchiveOutputStream = new TarArchiveOutputStream(new GzipCompressorOutputStream(new BufferedOutputStream(new FileOutputStream(tarGzFile))));
				FileInputStream fileInputStream = new FileInputStream(fileToCompress)) {
			// Defaults would reject paths longer than 100 characters and files larger than 8 GiB
			tarArchiveOutputStream.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
			tarArchiveOutputStream.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
			tarArchiveOutputStream.putArchiveEntry(new TarArchiveEntry(fileToCompress, filePathInTarGzFile));
			fileInputStream.transferTo(tarArchiveOutputStream);
			tarArchiveOutputStream.closeArchiveEntry();
			tarArchiveOutputStream.finish();
		} catch (final IOException | RuntimeException e) {
			if (tarGzFile.exists()) {
				tarGzFile.delete();
			}
			throw e;
		}
	}

	/**
	 * Opens the first file of a tar.gz archive for reading.
	 *
	 * @param tarGzFile tar.gz archive to read
	 * @return stream positioned at the content of the first file, the caller must close it
	 * @throws Exception if the archive does not exist, cannot be read or contains no file
	 */
	public static InputStream openCompressedFile(final File tarGzFile) throws Exception {
		return openCompressedFile(tarGzFile, null);
	}

	/**
	 * Opens a file of a tar.gz archive for reading. A leading "./" is ignored when comparing paths.
	 *
	 * @param tarGzFile tar.gz archive to read
	 * @param filePathInTarGzFile path of the file inside the archive, or null for the first file
	 * @return stream positioned at the content of the file, the caller must close it
	 * @throws Exception if the archive does not exist, cannot be read or does not contain the file
	 */
	public static InputStream openCompressedFile(final File tarGzFile, final String filePathInTarGzFile) throws Exception {
		checkTarGzFileExists(tarGzFile);

		final String searchedFilePath = Utilities.isBlank(filePathInTarGzFile) ? null : normalizeEntryPath(filePathInTarGzFile);
		TarArchiveInputStream tarArchiveInputStream = null;
		try {
			tarArchiveInputStream = openTarArchiveInputStream(tarGzFile);
			TarArchiveEntry entry = null;
			while ((entry = getNextEntry(tarArchiveInputStream)) != null) {
				if (isRegularFile(entry)) {
					if (searchedFilePath == null || normalizeEntryPath(entry.getName()).equals(searchedFilePath)) {
						return tarArchiveInputStream;
					}
				}
			}

			if (searchedFilePath == null) {
				throw new Exception("TarGz file '" + tarGzFile.getAbsolutePath() + "' is empty");
			} else {
				throw new Exception("TarGz file does not contain file '" + filePathInTarGzFile + "'");
			}
		} catch (final Exception e) {
			try {
				if (tarArchiveInputStream != null) {
					tarArchiveInputStream.close();
				}
			} catch (@SuppressWarnings("unused") final IOException e1) {
				// Do nothing
			}
			throw e;
		}
	}

	private static void checkTarGzFileExists(final File tarGzFile) throws Exception {
		if (!tarGzFile.exists()) {
			throw new Exception("TarGz file does not exist: " + tarGzFile.getAbsolutePath());
		} else if (!tarGzFile.isFile()) {
			throw new Exception("TarGz file path is not a file: " + tarGzFile.getAbsolutePath());
		}
	}

	private static TarArchiveInputStream openTarArchiveInputStream(final File tarGzFile) throws IOException {
		final FileInputStream fileInputStream = new FileInputStream(tarGzFile);
		try {
			return new TarArchiveInputStream(new GzipCompressorInputStream(new BufferedInputStream(fileInputStream)));
		} catch (final IOException | RuntimeException e) {
			// An invalid gzip header would otherwise leave the file open
			fileInputStream.close();
			throw e;
		}
	}

	/**
	 * getNextTarEntry is deprecated since commons-compress 1.25, but getNextEntry only returns a TarArchiveEntry since
	 * then. Using it in one place keeps the code compatible with older and newer versions.
	 */
	private static TarArchiveEntry getNextEntry(final TarArchiveInputStream tarArchiveInputStream) throws IOException {
		return tarArchiveInputStream.getNextEntry();
	}

	/**
	 * Regular files carry content, in contrast to directories, links and special files.
	 */
	private static boolean isRegularFile(final TarArchiveEntry entry) {
		return !entry.isDirectory() && !entry.isSymbolicLink() && !entry.isLink() && !entry.isCharacterDevice() && !entry.isBlockDevice() && !entry.isFIFO();
	}

	/**
	 * Uses "/" as separator and removes leading "./" (as written by "tar -C dir .").
	 */
	private static String normalizeEntryPath(final String entryPath) {
		String normalizedPath = entryPath.replace("\\", "/");
		while (normalizedPath.startsWith("./")) {
			normalizedPath = normalizedPath.substring(2);
		}
		return normalizedPath;
	}
}
