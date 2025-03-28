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

package fr.pilato.elasticsearch.crawler.fs.settings;

import fr.pilato.elasticsearch.crawler.fs.framework.FsCrawlerIllegalConfigurationException;
import fr.pilato.elasticsearch.crawler.fs.framework.MetaFileHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.github.gestalt.config.Gestalt;
import org.github.gestalt.config.builder.GestaltBuilder;
import org.github.gestalt.config.exceptions.GestaltException;
import org.github.gestalt.config.source.ClassPathConfigSourceBuilder;
import org.github.gestalt.config.source.FileConfigSourceBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Provides utility methods to read and write settings files
 */
public class FsSettingsLoader extends MetaFileHandler {

    private static final Logger logger = LogManager.getLogger();

    public static final String SETTINGS_JSON = "_settings.json";
    public static final String SETTINGS_YAML = "_settings.yaml";
    public static final String SETTINGS_DIR = "_settings";
    public static final String DEFAULT_SETTINGS = "/fr/pilato/elasticsearch/crawler/fs/settings/fscrawler-default.yaml";

    public FsSettingsLoader(Path root) {
        super(root);
    }

    /**
     * We read settings in ~/.fscrawler/{job_name}/_settings.[json|yml]
     * @param jobName is the job_name
     * @return Settings settings
     * @throws IOException in case of error while reading
     */
    public FsSettings read(String jobName) throws IOException {
        try {
            Path configYaml = root.resolve(jobName).resolve(SETTINGS_YAML);
            logger.trace("Trying to read settings from [{}] file", configYaml);
            if (Files.exists(configYaml)) {
                return load(configYaml);
            }
            Path configJson = root.resolve(jobName).resolve(SETTINGS_JSON);
            logger.trace("Trying to read settings from [{}] file", configJson);
            if (Files.exists(configJson)) {
                return load(configJson);
            }
            Path configDir = root.resolve(jobName).resolve(SETTINGS_DIR);
            logger.trace("Trying to read settings from [{}] directory", configDir);
            if (Files.exists(configDir)) {
                return load(readDir(configDir).toArray(new Path[0]));
            }
            throw new FsCrawlerIllegalConfigurationException("Can not read settings from " +
                    root.toAbsolutePath() + " with either /_settings.yaml, /_settings.json, /_settings/*.");
        } catch (GestaltException e) {
            throw new FsCrawlerIllegalConfigurationException("Can not parse the configuration files for " + jobName, e);
        }
    }

    /**
     * We write settings to ~/.fscrawler/{job_name}/_settings.yaml
     * @param settings Settings to write (settings.getName() contains the job name)
     * @throws IOException in case of error while reading
     */
    public void write(FsSettings settings) throws IOException {
        writeFile(settings.getName(), SETTINGS_YAML, FsSettingsParser.toYaml(settings));
    }

    /**
     * Read all files in ~/.fscrawler/{job_name}/_settings directory
     * @param settingsDir is _settings directory
     * @return The list of setting files
     */
    private List<Path> readDir(Path settingsDir) throws IOException {
        try (Stream<Path> files = Files.list(settingsDir)) {
            return files.collect(Collectors.toList());
        }
    }

    /**
     * Load settings from files. It could be both json or yaml files
     * @param configFiles list of files to read
     * @return The settings
     * @throws GestaltException when we can not read settings
     */
    private FsSettings load(Path... configFiles) throws GestaltException {
        GestaltBuilder builder = new GestaltBuilder()
                .addSource(ClassPathConfigSourceBuilder.builder().setResource(DEFAULT_SETTINGS).build());

        for (Path configFile : configFiles) {
            builder.addSource(FileConfigSourceBuilder.builder().setPath(configFile).build());
        }

        builder.setTreatMissingDiscretionaryValuesAsErrors(false);

        Gestalt gestalt = builder.build();

        // Loads and parses the configurations, this will throw exceptions if there are any errors.
        gestalt.loadConfigs();

        FsSettings settings = new FsSettings();

        settings.setName(gestalt.getConfig("name", String.class));
        settings.setFs(gestalt.getConfigOptional("fs", Fs.class).orElse(null));
        settings.setElasticsearch(gestalt.getConfigOptional("elasticsearch", Elasticsearch.class).orElse(null));
        settings.setTags(gestalt.getConfigOptional("fs.tags", Tags.class).orElse(null));
        settings.setServer(gestalt.getConfigOptional("server", Server.class).orElse(null));
        settings.setRest(gestalt.getConfigOptional("rest", Rest.class).orElse(null));

        logger.debug("Successfully loaded settings from {}", (Object) configFiles);
        logger.trace("Loaded settings [{}]", settings);

        return settings;
    }
}
