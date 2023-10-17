# File System Crawler for Elasticsearch

Welcome to the FS Crawler for [Elasticsearch](https://elastic.co/)

This crawler helps to index binary documents such as PDF, Open Office, MS Office.

**Main features**:

* Local file system (or a mounted drive) crawling and index new files, update existing ones and removes old ones.
* Remote file system over SSH/FTP crawling.
* REST interface to let you "upload" your binary documents to elasticsearch.

Current "most stable" versions are:

| Elasticsearch | FS Crawler    | Released   | Docs                                                                          |
|---------------|---------------|------------|-------------------------------------------------------------------------------|
| 6.x, 7.x, 8.x | 2.10-SNAPSHOT |            | [2.10-SNAPSHOT](https://fscrawler.readthedocs.io/en/latest/)                  |


## Build and Quality Status

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/fr.pilato.elasticsearch.crawler/fscrawler-distribution/badge.svg?style=flat-square)](https://maven-badges.herokuapp.com/maven-central/fr.pilato.elasticsearch.crawler/fscrawler-distribution/)
[![Build](https://github.com/dadoonet/fscrawler/actions/workflows/maven.yml/badge.svg)](https://github.com/dadoonet/fscrawler/actions/workflows/maven.yml)
[![Documentation Status](https://readthedocs.org/projects/fscrawler/badge/?version=latest)](https://fscrawler.readthedocs.io/en/latest/?badge=latest)

[![Lines of Code](https://sonarcloud.io/api/project_badges/measure?project=dadoonet_fscrawler&metric=ncloc)](https://sonarcloud.io/summary/new_code?id=dadoonet_fscrawler)
[![Duplicated Lines (%)](https://sonarcloud.io/api/project_badges/measure?project=dadoonet_fscrawler&metric=duplicated_lines_density)](https://sonarcloud.io/summary/new_code?id=dadoonet_fscrawler)
[![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=dadoonet_fscrawler&metric=sqale_rating)](https://sonarcloud.io/summary/new_code?id=dadoonet_fscrawler)
[![Technical Debt](https://sonarcloud.io/api/project_badges/measure?project=dadoonet_fscrawler&metric=sqale_index)](https://sonarcloud.io/summary/new_code?id=dadoonet_fscrawler)
[![Reliability Rating](https://sonarcloud.io/api/project_badges/measure?project=dadoonet_fscrawler&metric=reliability_rating)](https://sonarcloud.io/summary/new_code?id=dadoonet_fscrawler)

[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=dadoonet_fscrawler&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=dadoonet_fscrawler)
[![Bugs](https://sonarcloud.io/api/project_badges/measure?project=dadoonet_fscrawler&metric=bugs)](https://sonarcloud.io/summary/new_code?id=dadoonet_fscrawler)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=dadoonet_fscrawler&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=dadoonet_fscrawler)
[![Code Smells](https://sonarcloud.io/api/project_badges/measure?project=dadoonet_fscrawler&metric=code_smells)](https://sonarcloud.io/summary/new_code?id=dadoonet_fscrawler)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=dadoonet_fscrawler&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=dadoonet_fscrawler)

The guide has been moved to [ReadTheDocs](https://fscrawler.readthedocs.io/en/latest/).

## Contribute

Works on my machine - and yours ! Spin up pre-configured, standardized dev environments of this repository, by clicking on the button below.

[![Open in Gitpod](https://gitpod.io/button/open-in-gitpod.svg)](https://gitpod.io/#/https://github.com/dadoonet/fscrawler)

# License

Read more about the [License](https://fscrawler.readthedocs.io/en/latest/index.html#license).

# Thanks

Thanks to [JetBrains](https://www.jetbrains.com/?from=FSCrawler) for the IntelliJ IDEA License!

Thanks to SonarCloud for the free analysis!

[![SonarCloud](https://sonarcloud.io/images/project_badges/sonarcloud-white.svg)](https://sonarcloud.io/summary/new_code?id=dadoonet_fscrawler)
