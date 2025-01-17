/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.installer.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.MessageFormat;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import mjson.Json;

import net.fabricmc.installer.LoaderVersion;
import net.fabricmc.installer.util.FabricService;
import net.fabricmc.installer.util.InstallerProgress;
import net.fabricmc.installer.util.Library;
import net.fabricmc.installer.util.Reference;
import net.fabricmc.installer.util.Utils;

public class ClientInstaller {
	public static String install(Path mcDir, String gameVersion, LoaderVersion loaderVersion, InstallerProgress progress) throws IOException {
		System.out.println("Installing " + gameVersion + " with fabric " + loaderVersion.name);

		String profileName = String.format("%s-%s-%s", Reference.LOADER_NAME, loaderVersion.name, gameVersion);

		Path versionsDir = mcDir.resolve("versions");
		Path profileDir = versionsDir.resolve(profileName);
		Path profileJson = profileDir.resolve(profileName + ".json");

		if (!Files.exists(profileDir)) {
			Files.createDirectories(profileDir);
		}

		Path profileJar = profileDir.resolve(profileName + ".jar");
		Files.deleteIfExists(profileJar);

		Json json = FabricService.queryMetaJson(String.format("v2/versions/loader/%s/%s/profile/json", gameVersion, loaderVersion.name));
		Files.write(profileJson, json.toString().getBytes(StandardCharsets.UTF_8));

		/*
		Downloading the libraries isn't strictly necessary as the launcher will do it for us.
		Do it anyway in case the launcher fails, we know we have a working connection to maven here.
		 */
		Path libsDir = mcDir.resolve("libraries");

		for (Json libraryJson : json.at("libraries").asJsonList()) {
			Library library = new Library(libraryJson);
			Path libraryFile = libsDir.resolve(library.getPath());
			String url = library.getURL();

			//System.out.println("Downloading "+url+" to "+libraryFile);
			progress.updateProgress(new MessageFormat(Utils.BUNDLE.getString("progress.download.library.entry")).format(new Object[]{library.name}));
			FabricService.downloadSubstitutedMaven(url, libraryFile);
		}

		progress.updateProgress(Utils.BUNDLE.getString("progress.done"));

		return profileName;
	}

	public static String installFromZip(Path mcDir, Path zipFilePath, InstallerProgress progress) throws IOException {
		System.out.println("Installing from zip file: " + zipFilePath.toString());

		// Get the name of the ZIP file without extension
		String zipFileName = zipFilePath.getFileName().toString().replaceFirst("[.][^.]+$", "");
		System.out.println(zipFileName);
		// Create the profile directory based on the ZIP file name
		Path versionsDir = mcDir.resolve("versions");
		Path profileDir = versionsDir.resolve(zipFileName);

		if (!Files.exists(profileDir)) {
			Files.createDirectories(profileDir);
		}

		Path tempDir = Files.createTempDirectory("fabric-installer");

		try (ZipInputStream zipInputStream = new ZipInputStream(Files.newInputStream(zipFilePath))) {
			ZipEntry entry;

			while ((entry = zipInputStream.getNextEntry()) != null) {
				if (!entry.isDirectory()) {
					Path entryPath = Paths.get(entry.getName());

					Path filePath = tempDir.resolve(entryPath);

					Files.createDirectories(filePath.getParent());

					Files.copy(zipInputStream, filePath, StandardCopyOption.REPLACE_EXISTING);
				} else {
					Files.createDirectories(tempDir.resolve(entry.getName()));
				}

				zipInputStream.closeEntry();
			}
		}

		// Move the extracted contents to the profile directory
		try (Stream<Path> stream = Files.walk(tempDir)) {
			stream.forEach(file -> {
				try {
					Path relativePath = tempDir.relativize(file);

					Path dest = profileDir.resolve(relativePath);

					if (!Files.isDirectory(file)) {
						Files.createDirectories(dest.getParent());

						Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
			});
		}

		progress.updateProgress(Utils.BUNDLE.getString("progress.done"));

		return zipFileName;
	}
}
