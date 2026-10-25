package de.soderer.zip;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class Zip4jUtilitiesTest {
	private static final char[] PASSWORD = "pässword".toCharArray();

	@TempDir
	File tempDirectory;

	/**
	 * Creates an unencrypted zip file with the given entries (via the JDK), entry names ending with "/" are directories.
	 * Each file entry contains its own name as content.
	 */
	private File createZip(final String fileName, final String... entryNames) throws IOException {
		final File zipFile = new File(tempDirectory, fileName);
		try (ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(zipFile))) {
			for (final String entryName : entryNames) {
				zipOutputStream.putNextEntry(new ZipEntry(entryName));
				if (!entryName.endsWith("/")) {
					zipOutputStream.write(entryName.getBytes(StandardCharsets.UTF_8));
				}
				zipOutputStream.closeEntry();
			}
		}
		return zipFile;
	}

	private static byte[] content(final String entryName) {
		return entryName.getBytes(StandardCharsets.UTF_8);
	}

	@Test
	public void testPasswordSecuredZipFileWithAes() throws Exception {
		final File zipFile = createZip("data.csv.zip", "data.csv");
		Zip4jUtilities.createPasswordSecuredZipFile(zipFile.getAbsolutePath(), PASSWORD, false);

		// The entry is named like the zip file without ".zip"
		assertEquals(List.of("data.csv"), Zip4jUtilities.getZipFileEntries(zipFile, PASSWORD));
		assertEquals(content("data.csv").length, Zip4jUtilities.getUncompressedSize(zipFile, PASSWORD));
		try (InputStream inputStream = Zip4jUtilities.openPasswordSecuredZipFile(zipFile.getAbsolutePath(), PASSWORD)) {
			assertArrayEquals(content("data.csv"), inputStream.readAllBytes());
		}
		// No temp file remains
		assertEquals(1, tempDirectory.list().length);
	}

	@Test
	public void testPasswordSecuredZipFileWithZipCrypto() throws Exception {
		// A file name without ".zip" is taken as entry name unchanged
		final File zipFile = createZip("archive", "content.txt");
		Zip4jUtilities.createPasswordSecuredZipFile(zipFile.getAbsolutePath(), PASSWORD, true);

		assertEquals(List.of("archive"), Zip4jUtilities.getZipFileEntries(zipFile, PASSWORD));
		try (InputStream inputStream = Zip4jUtilities.openPasswordSecuredZipFile(zipFile.getAbsolutePath(), PASSWORD, "archive")) {
			assertArrayEquals(content("content.txt"), inputStream.readAllBytes());
		}
	}

	@Test
	public void testWrongPassword() throws Exception {
		final File zipFile = createZip("data.zip", "data.txt");
		Zip4jUtilities.createPasswordSecuredZipFile(zipFile.getAbsolutePath(), PASSWORD, false);

		assertThrows(Exception.class, () -> {
			try (InputStream inputStream = Zip4jUtilities.openPasswordSecuredZipFile(zipFile.getAbsolutePath(), "wrong".toCharArray())) {
				inputStream.readAllBytes();
			}
		});
	}

	@Test
	public void testCreatePasswordSecuredZipFileErrors() throws Exception {
		assertThrows(IOException.class, () -> Zip4jUtilities.createPasswordSecuredZipFile(new File(tempDirectory, "missing.zip").getAbsolutePath(), PASSWORD, false));
		assertThrows(IOException.class, () -> Zip4jUtilities.createPasswordSecuredZipFile(tempDirectory.getAbsolutePath(), PASSWORD, false));

		// Only zip files with exactly one file can be secured, others stay unchanged
		for (final String[] entries : new String[][] { { "a.txt", "b.txt" }, { "dir/" } }) {
			final File zipFile = createZip("invalid.zip", entries);
			final byte[] originalData = Files.readAllBytes(zipFile.toPath());
			final IOException exception = assertThrows(IOException.class, () -> Zip4jUtilities.createPasswordSecuredZipFile(zipFile.getAbsolutePath(), PASSWORD, false));
			assertTrue(exception.getMessage().contains("must contain exactly one file"), exception.getMessage());
			assertArrayEquals(originalData, Files.readAllBytes(zipFile.toPath()));
			assertEquals(1, tempDirectory.list().length);
			zipFile.delete();
		}
	}

	@Test
	public void testUnencryptedZipFile() throws Exception {
		final File zipFile = createZip("plain.zip", "dir/", "dir/a.txt", "b.txt");
		assertEquals(List.of("dir/", "dir/a.txt", "b.txt"), Zip4jUtilities.getZipFileEntries(zipFile, null));
		assertEquals(content("dir/a.txt").length + content("b.txt").length, Zip4jUtilities.getUncompressedSize(zipFile, null));
		try (InputStream inputStream = Zip4jUtilities.openPasswordSecuredZipFile(zipFile.getAbsolutePath(), null, "dir/a.txt")) {
			assertArrayEquals(content("dir/a.txt"), inputStream.readAllBytes());
		}
	}

	@Test
	public void testOpenPasswordSecuredZipFileErrors() throws Exception {
		assertThrows(IOException.class, () -> Zip4jUtilities.openPasswordSecuredZipFile(new File(tempDirectory, "missing.zip").getAbsolutePath(), PASSWORD));
		assertThrows(IOException.class, () -> Zip4jUtilities.openPasswordSecuredZipFile(tempDirectory.getAbsolutePath(), PASSWORD));

		final File directoriesOnly = createZip("directories.zip", "dir/");
		assertTrue(assertThrows(Exception.class, () -> Zip4jUtilities.openPasswordSecuredZipFile(directoriesOnly.getAbsolutePath(), null)).getMessage().contains("contains no file"));

		final File twoFiles = createZip("two.zip", "a.txt", "b.txt");
		assertTrue(assertThrows(Exception.class, () -> Zip4jUtilities.openPasswordSecuredZipFile(twoFiles.getAbsolutePath(), null)).getMessage().contains("more than one file"));
		assertTrue(assertThrows(Exception.class, () -> Zip4jUtilities.openPasswordSecuredZipFile(twoFiles.getAbsolutePath(), null, "c.txt")).getMessage().contains("does not include"));
	}

	@Test
	public void testReadingErrors() {
		final File missingFile = new File(tempDirectory, "missing.zip");
		assertThrows(IOException.class, () -> Zip4jUtilities.getZipFileEntries(missingFile, null));
		assertThrows(IOException.class, () -> Zip4jUtilities.getUncompressedSize(missingFile, null));
		assertThrows(IOException.class, () -> Zip4jUtilities.getZipFileEntries(tempDirectory, null));
	}
}
