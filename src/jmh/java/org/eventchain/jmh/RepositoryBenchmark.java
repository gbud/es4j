/**
 * Copyright 2016 Eventchain team
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
 */
package org.eventchain.jmh;

import com.googlecode.cqengine.query.option.QueryOptions;
import org.eventchain.*;
import org.eventchain.annotations.Index;
import org.eventchain.hlc.NTPServerTimeProvider;
import org.eventchain.index.IndexEngine;
import org.eventchain.index.SimpleAttribute;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import static org.eventchain.index.IndexEngine.IndexFeature.EQ;

@State(Scope.Benchmark)
public abstract class RepositoryBenchmark {

    private Repository repository;
    private Journal journal;
    private IndexEngine indexEngine;
    private MemoryLockProvider lockProvider;

    @Setup
    public void setup() throws Exception {
        repository = Repository.create();

        journal = createJournal();

        repository.setJournal(journal);

        NTPServerTimeProvider timeProvider = new NTPServerTimeProvider(new String[]{"localhost"});
        repository.setPhysicalTimeProvider(timeProvider);

        indexEngine = createIndex();
        repository.setIndexEngine(indexEngine);

        lockProvider = new MemoryLockProvider();
        repository.setLockProvider(lockProvider);

        repository.addCommandSetProvider(new PackageCommandSetProvider(new Package[]{RepositoryBenchmark.class.getPackage()}));
        repository.addEventSetProvider(new PackageEventSetProvider(new Package[]{RepositoryBenchmark.class.getPackage()}));

        repository.startAsync().awaitRunning();
    }

    protected abstract IndexEngine createIndex();

    protected abstract Journal createJournal();

    @TearDown
    public void teardown() {
        repository.stopAsync().awaitTerminated();
    }


    public static class TestEvent extends Event {
        private String string;

        @Index({EQ})
        public static SimpleAttribute<TestEvent, String> ATTR = new SimpleAttribute<TestEvent, String>("attr") {
            @Override
            public String getValue(TestEvent object, QueryOptions queryOptions) {
                return object.string();
            }
        };

        public String toString() {
            return "org.eventchain.jmh.RepositoryBenchmark.TestEvent(string=" + this.string + ")";
        }

        public String string() {
            return this.string;
        }

        public TestEvent string(String string) {
            this.string = string;
            return this;
        }
    }

    public static class TestCommand extends Command<String> {
        @Override
        public Stream<Event> events(Repository repository) {
            return Stream.of(new TestEvent().string("test"));
        }

        @Override
        public String onCompletion() {
            return "hello, world";
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.All)
    public void basicPublish() throws ExecutionException, InterruptedException {
        repository.publish(new TestCommand()).get();
    }


}
