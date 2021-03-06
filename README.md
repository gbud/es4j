[![Build Status](https://travis-ci.org/eventsourcing/es4j.svg?branch=master)](https://travis-ci.org/eventsourcing/es4j)
[ ![Download](https://api.bintray.com/packages/eventsourcing/maven/eventsourcing-core/images/download.svg) ](https://bintray.com/eventsourcing/maven/eventsourcing-core/_latestVersion)
[ ![Download](https://api.bintray.com/packages/eventsourcing/maven-snapshots/eventsourcing-core/images/download.svg) ](https://bintray.com/eventsourcing/maven-snapshots/eventsourcing-core/_latestVersion)
[![Join the chat at https://gitter.im/eventsourcing/eventsourcing](https://badges.gitter.im/eventsourcing/eventsourcing.svg)](https://gitter.im/eventsourcing/eventsourcing?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

# ![logo](https://eventsourcing.com/android-icon-48x48.png) Eventsourcing for Java

Instead of mutating data in a database, it stores all changes (events) and causes (commands). This facilitates rapid application development and evolution by mitigating the inability to predict how future needs will drive data shape requirements as all causal information is persisted. It also provides a foundation for deep analytics, data recovery, audit trails and other associated benefits.

To learn what kind of problems ES4J addresses, please read [Why Use Eventsourcing Database](https://blog.eventsourcing.com/why-use-eventsourcing-database-6b5e2ac61848)

## Key benefits

* Flexibility of data aggregation and representation
* Persistence of causal information
* Succinctly mapped application functionality
* Undo/redo functionality
* Audit trail logging

## Key features

* Clean, succinct Command/Event model
* Compact data storage layout
* Using [Disruptor](https://lmax-exchange.github.io/disruptor/) for fast message processing
* Using [CQengine](https://github.com/npgall/cqengine) for fast indexing and querying
* In-memory and on-disk (*more persistent indices coming soon*) storage
* Causality-preserving [Hybrid Logical Clocks](http://www.cse.buffalo.edu/tech-reports/2014-04.pdf)
* Locking synchronization primitive

# Presentation

You can find our current slide deck at https://eventsourcing.com/presentation

# Documentation

Installation instructions and documentation can be found at [es4j-doc.eventsourcing.com](http://es4j-doc.eventsourcing.com)

We strive to specify the building blocks behind Eventsourcing and its ecosystem as succinct specifications, you can find the current list of them at [rfc.eventsourcing.com](http://rfc.eventsourcing.com)

# Roadmap

As this project is striving to be a decentralized, community-driven project governed by the [C4 process](http://rfc.unprotocols.org/spec:1/C4), there is no central roadmap per se. However, individual
contributors are free to publish their own roadmaps to help indicating their intentions. Current roadmaps available:

* [Yurii Rashkovskii](https://github.com/yrashk/es4j/milestones/Roadmap)

# Snapshot versions

Every successful build is published into a [separate Maven repository on Bintray](https://bintray.com/eventsourcing/maven-snapshots) (using a `git describe`
version), you can find the last snapshot version mentioned in a badge at the top of this file.

Gradle configuration:

```groovy
repositories {
    maven {
        url  "http://dl.bintray.com/eventsourcing/maven-snapshots"
    }
}
```

Maven configuration:

```xml
<?xml version='1.0' encoding='UTF-8'?>
<settings xsi:schemaLocation='http://maven.apache.org/SETTINGS/1.0.0 http://maven.apache.org/xsd/settings-1.0.0.xsd' xmlns='http://maven.apache.org/SETTINGS/1.0.0' xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'>
<profiles>
	<profile>
		<repositories>
			<repository>
				<snapshots>
					<enabled>false</enabled>
				</snapshots>
				<id>bintray-eventsourcing-maven-snapshots</id>
				<name>bintray</name>
				<url>http://dl.bintray.com/eventsourcing/maven-snapshots</url>
			</repository>
		</repositories>
		<id>bintray</id>
	</profile>
</profiles>
<activeProfiles>
	<activeProfile>bintray</activeProfile>
</activeProfiles>
</settings>
```

# Related projects

* [es4j-graphql](https://github.com/eventsourcing/es4j-graphql) A Relay.js/GraphQL adaptor for ES4J-based applications.

# Contributing

Contributions of all kinds (code, documentation, testing, artwork, etc.) are highly encouraged. Please open a GitHub issue if you want to suggest an idea or ask a question. We use Unprotocols [C4 process](http://rfc.unprotocols.org/1/).

For more details, please refer to [CONTRIBUTING](CONTRIBUTING.md)
