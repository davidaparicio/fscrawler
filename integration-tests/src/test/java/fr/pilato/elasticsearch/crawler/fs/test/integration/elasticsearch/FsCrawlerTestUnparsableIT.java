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

package fr.pilato.elasticsearch.crawler.fs.test.integration.elasticsearch;

import fr.pilato.elasticsearch.crawler.fs.client.ESSearchRequest;
import fr.pilato.elasticsearch.crawler.fs.settings.FsSettings;
import fr.pilato.elasticsearch.crawler.fs.test.integration.AbstractFsCrawlerITCase;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Test;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;

import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Test crawler with unparsable files
 */
public class FsCrawlerTestUnparsableIT extends AbstractFsCrawlerITCase {
    private static final Logger logger = LogManager.getLogger();

    /**
     * Test for #105: <a href="https://github.com/dadoonet/fscrawler/issues/105">https://github.com/dadoonet/fscrawler/issues/105</a>
     */
    @Test
    public void unparsable() throws Exception {
        crawler = startCrawler();

        // We expect to have two files
        countTestHelper(new ESSearchRequest().withIndex(getCrawlerName()), 2L, null);
    }

    /**
     * Test case for <a href="https://github.com/dadoonet/fscrawler/issues/362">https://github.com/dadoonet/fscrawler/issues/362</a>
     * @throws Exception In case something is wrong
     */
    @Test
    public void non_readable_file() throws Exception {
        // We change the attributes of the file
        logger.info(" ---> Changing attributes for file roottxtfile.txt");

        boolean isPosix =
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

        assumeThat(isPosix)
                .describedAs("This test can only run on Posix systems")
                .isTrue();

        Files.getFileAttributeView(currentTestResourceDir.resolve("roottxtfile.txt"), PosixFileAttributeView.class)
                .setPermissions(EnumSet.noneOf(PosixFilePermission.class));

        FsSettings fsSettings = createTestSettings();
        fsSettings.getFs().setIndexContent(false);
        crawler = startCrawler(fsSettings);

        // We should have one doc first
        countTestHelper(new ESSearchRequest().withIndex(getCrawlerName()), 1L, currentTestResourceDir);
    }
}
