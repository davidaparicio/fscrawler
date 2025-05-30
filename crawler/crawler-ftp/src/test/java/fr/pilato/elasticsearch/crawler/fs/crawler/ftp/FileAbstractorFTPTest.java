/*
 * Licensed to David Pilato (the "Author") under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Author licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package fr.pilato.elasticsearch.crawler.fs.crawler.ftp;

import static org.assertj.core.api.Assertions.assertThat;

import fr.pilato.elasticsearch.crawler.fs.crawler.FileAbstractModel;
import fr.pilato.elasticsearch.crawler.fs.settings.FsSettings;
import fr.pilato.elasticsearch.crawler.fs.settings.FsSettingsLoader;
import fr.pilato.elasticsearch.crawler.fs.test.framework.AbstractFSCrawlerTestCase;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.mockftpserver.fake.FakeFtpServer;
import org.mockftpserver.fake.UserAccount;
import org.mockftpserver.fake.filesystem.DirectoryEntry;
import org.mockftpserver.fake.filesystem.FileEntry;
import org.mockftpserver.fake.filesystem.FileSystem;
import org.mockftpserver.fake.filesystem.Permissions;
import org.mockftpserver.fake.filesystem.UnixFakeFileSystem;

public class FileAbstractorFTPTest extends AbstractFSCrawlerTestCase {
    private static final Logger logger = LogManager.getLogger();
    private FakeFtpServer fakeFtpServer;
    private final String nestedDir = "/nested";
    private final String permissionDir = "/permission";
    private final String user = "user";
    private final String pass = "pass";

    @Before
    public void setup() {
        fakeFtpServer = new FakeFtpServer();
        fakeFtpServer.setServerControlPort(5968);
        fakeFtpServer.addUserAccount(new UserAccount(user, pass, "/"));
        FileSystem fileSystem = new UnixFakeFileSystem();

        fileSystem.add(new DirectoryEntry(nestedDir));
        fileSystem.add(new FileEntry(nestedDir + "/foo.txt", "文件名不支持中文"));
        fileSystem.add(new FileEntry(nestedDir + "/bar.txt", "filename doesn't support utf-8"));

        fileSystem.add(new DirectoryEntry(nestedDir + "/buzz"));
        fileSystem.add(new FileEntry(nestedDir + "/buzz/hello.txt", "hello"));
        fileSystem.add(new FileEntry(nestedDir + "/buzz/world.txt", "world"));

        fileSystem.add(new DirectoryEntry(permissionDir));
        FileEntry fileAllPermissions = new FileEntry(permissionDir + "/all.txt", "123");
        fileAllPermissions.setPermissions(Permissions.ALL);
        fileSystem.add(fileAllPermissions);
        FileEntry fileNonePermissions = new FileEntry(permissionDir + "/none.txt", "456");
        fileNonePermissions.setPermissions(Permissions.NONE);
        fileSystem.add(fileNonePermissions);

        fakeFtpServer.setFileSystem(fileSystem);
        fakeFtpServer.start();
    }

    @After
    public void shutDown() {
        fakeFtpServer.stop();
    }

    @Test
    public void connectToFakeFTPServer() throws Exception {
        int port = fakeFtpServer.getServerControlPort();
        FsSettings fsSettings = FsSettingsLoader.load();
        fsSettings.getServer().setHostname("localhost");
        fsSettings.getServer().setUsername(user);
        fsSettings.getServer().setPassword(pass);
        fsSettings.getServer().setPort(port);
        FileAbstractorFTP ftp = new FileAbstractorFTP(fsSettings);
        ftp.open();
        boolean exists = ftp.exists(nestedDir);
        assertThat(exists).isTrue();
        Collection<FileAbstractModel> files = ftp.getFiles(nestedDir);
        assertThat(files).hasSize(3);

        for (FileAbstractModel file : files) {
            if (file.isDirectory()) {
                assertThat(file.getName()).isEqualTo("buzz");
                Collection<FileAbstractModel> subDirFiles = ftp.getFiles(file.getFullpath());
                assertThat(subDirFiles).hasSize(2);
                logger.debug("Found {} files in sub dir", subDirFiles.size());
                for (FileAbstractModel subDirFile : subDirFiles) {
                    InputStream inputStream = ftp.getInputStream(subDirFile);
                    String content = IOUtils.toString(inputStream, StandardCharsets.UTF_8);
                    logger.debug("[{}] - {}: {}", file.getName(), subDirFile.getName(), content);
                    ftp.closeInputStream(inputStream);
                }
            } else {
                InputStream inputStream = ftp.getInputStream(file);
                String content = IOUtils.toString(inputStream, StandardCharsets.UTF_8);
                logger.debug(" - {}: {}", file.getName(), content);
                ftp.closeInputStream(inputStream);
            }
        }

        ftp.close();
    }

    /**
     * FakeFtpServer doesn't support utf-8
     * You have to adapt this test to your own system
     * So this test is disabled by default
     */
    @Test @Ignore
    public void testConnectToFTPServer() throws Exception {
        String path = "/中文目录";
        FsSettings fsSettings = FsSettingsLoader.load();
        fsSettings.getServer().setHostname("192.168.18.207");
        fsSettings.getServer().setUsername("helsonxiao");
        fsSettings.getServer().setPassword("123456");
        fsSettings.getServer().setPort(21);

        FileAbstractorFTP ftp = new FileAbstractorFTP(fsSettings);
        ftp.open();
        boolean exists = ftp.exists(path);
        assertThat(exists).isTrue();
        Collection<FileAbstractModel> files = ftp.getFiles(path);
        logger.debug("Found {} files", files.size());

        for (FileAbstractModel file : files) {
            if (file.isDirectory()) {
                Collection<FileAbstractModel> subDirFiles = ftp.getFiles(file.getFullpath());
                logger.debug("Found {} files in sub dir", subDirFiles.size());
                for (FileAbstractModel subDirFile : subDirFiles) {
                    if (subDirFile.isFile()) {
                        InputStream inputStream = ftp.getInputStream(subDirFile);
                        String content = IOUtils.toString(inputStream, StandardCharsets.UTF_8);
                        logger.debug("[{}] - {}: {}", file.getName(), subDirFile.getName(), content);
                        ftp.closeInputStream(inputStream);
                    }
                }
            } else {
                InputStream inputStream = ftp.getInputStream(file);
                String content = IOUtils.toString(inputStream, StandardCharsets.UTF_8);
                logger.debug(" - {}: {}", file.getName(), content);
                ftp.closeInputStream(inputStream);
            }
        }

        ftp.close();
    }

    @Test
    public void ftpFilePermissions() throws IOException {
        int port = fakeFtpServer.getServerControlPort();
        FsSettings fsSettings = FsSettingsLoader.load();
        fsSettings.getServer().setHostname("localhost");
        fsSettings.getServer().setUsername(user);
        fsSettings.getServer().setPassword(pass);
        fsSettings.getServer().setPort(port);

        FileAbstractorFTP ftp = new FileAbstractorFTP(fsSettings);
        ftp.open();

        Collection<FileAbstractModel> files = ftp.getFiles(permissionDir);
        assertThat(files).hasSize(2);
        List<String> filenames = files.stream().map(FileAbstractModel::getName).collect(Collectors.toList());
        assertThat(filenames).contains("all.txt", "none.txt");
        for (FileAbstractModel file : files) {
            if (file.getName().equals("all.txt")) {
                assertThat(file.getPermissions()).isEqualTo(777);
                InputStream inputStream = ftp.getInputStream(file);
                String content = IOUtils.toString(inputStream, StandardCharsets.UTF_8);
                logger.debug(" - {}: {}", file.getName(), content);
                ftp.closeInputStream(inputStream);
            } else if (file.getName().equals("none.txt")) {
                assertThat(file.getPermissions()).isZero();
                boolean errorOccurred = false;
                //noinspection EmptyTryBlock
                try (InputStream ignored = ftp.getInputStream(file)) {
                } catch (IOException e) {
                    errorOccurred = true;
                    logger.error(e.getMessage());
                }
                assertThat(errorOccurred).isTrue();
            }
        }

        ftp.close();
    }
}
