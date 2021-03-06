/**
 * Copyright (c) 2016, All Contributors (see CONTRIBUTORS file)
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.eventsourcing;

import boguspackage.BogusCommand;
import boguspackage.BogusEvent;
import com.eventsourcing.annotations.Index;
import com.eventsourcing.events.CommandTerminatedExceptionally;
import com.eventsourcing.events.EventCausalityEstablished;
import com.eventsourcing.hlc.HybridTimestamp;
import com.eventsourcing.hlc.NTPServerTimeProvider;
import com.eventsourcing.index.IndexEngine;
import com.eventsourcing.index.MemoryIndexEngine;
import com.eventsourcing.index.SimpleAttribute;
import com.eventsourcing.layout.LayoutConstructor;
import com.eventsourcing.repository.*;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;
import lombok.*;
import lombok.experimental.Accessors;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static com.eventsourcing.index.EntityQueryFactory.all;
import static com.googlecode.cqengine.query.QueryFactory.contains;
import static com.googlecode.cqengine.query.QueryFactory.equal;
import static org.testng.Assert.*;

public abstract class RepositoryTest<T extends Repository> {

    private final T repository;
    private Journal journal;
    private MemoryIndexEngine indexEngine;
    private LocalLockProvider lockProvider;
    private NTPServerTimeProvider timeProvider;

    public RepositoryTest(T repository) {
        this.repository = repository;
    }

    @BeforeClass
    public void setUpEnv() throws Exception {
        repository
                .addCommandSetProvider(new PackageCommandSetProvider(new Package[]{RepositoryTest.class.getPackage()}));
        repository.addEventSetProvider(new PackageEventSetProvider(new Package[]{RepositoryTest.class.getPackage()}));
        journal = createJournal();
        repository.setJournal(journal);
        timeProvider = new NTPServerTimeProvider(new String[]{"localhost"});
        repository.setPhysicalTimeProvider(timeProvider);
        indexEngine = new MemoryIndexEngine();
        repository.setIndexEngine(indexEngine);
        lockProvider = new LocalLockProvider();
        repository.setLockProvider(lockProvider);
        repository.startAsync().awaitRunning();
    }

    protected abstract Journal createJournal();

    @AfterClass
    public void tearDownEnv() throws Exception {
        repository.stopAsync().awaitTerminated();
    }

    @BeforeMethod
    public void setUp() throws Exception {
        journal.clear();
    }

    @Accessors(fluent = true) @ToString
    public static class TestEvent extends StandardEvent {
        @Getter
        private final String string;

        @Index({IndexEngine.IndexFeature.EQ, IndexEngine.IndexFeature.SC})
        public static SimpleAttribute<TestEvent, String> ATTR = new SimpleAttribute<TestEvent, String>() {
            @Override
            public String getValue(TestEvent object, QueryOptions queryOptions) {
                return object.string();
            }
        };

        @Builder
        public TestEvent(HybridTimestamp timestamp, String string) {
            super(timestamp);
            this.string = string;
        }
    }

    @ToString
    public static class RepositoryTestCommand extends StandardCommand<Void, String> {

        @Getter
        private final String value;

        @Builder
        public RepositoryTestCommand(HybridTimestamp timestamp, String value) {
            super(timestamp);
            this.value = value == null ? "test" : value;
        }


        @Override
        public EventStream<Void> events(Repository repository) {
            return EventStream.of(TestEvent.builder().string(value).build());
        }

        @Override
        public String result() {
            return "hello, world";
        }

        @Index({IndexEngine.IndexFeature.EQ, IndexEngine.IndexFeature.SC})
        public static SimpleAttribute<RepositoryTestCommand, String> ATTR = new
                SimpleAttribute<RepositoryTestCommand, String>("index") {
            @Override
            public String getValue(RepositoryTestCommand object, QueryOptions queryOptions) {
                return object.value;
            }
        };

    }

    @Test
    public void discovery() {
        assertTrue(repository.getCommands().contains(RepositoryTestCommand.class));
    }

    @Test
    @SneakyThrows
    public void basicPublish() {
        assertEquals("hello, world", repository.publish(RepositoryTestCommand.builder().build()).get());
    }

    @Test
    @SneakyThrows
    public void subscribe() {
        final AtomicBoolean gotEvent = new AtomicBoolean();
        final AtomicBoolean gotCommand = new AtomicBoolean();
        repository.addEntitySubscriber(new ClassEntitySubscriber<TestEvent>(TestEvent.class) {
            @Override public void onEntity(EntityHandle<TestEvent> entity) {
                gotEvent.set(journal.get(entity.uuid()).isPresent());
            }
        });
        repository.addEntitySubscriber(new ClassEntitySubscriber<RepositoryTestCommand>(RepositoryTestCommand.class) {
            @Override public void onEntity(EntityHandle<RepositoryTestCommand> entity) {
                gotCommand.set(journal.get(entity.uuid()).isPresent());
            }
        });
        repository.publish(RepositoryTestCommand.builder().build()).get();
        assertTrue(gotEvent.get());
        assertTrue(gotCommand.get());
    }

    @Test
    @SneakyThrows
    public void timestamping() {
        repository.publish(RepositoryTestCommand.builder().build()).get();
        IndexedCollection<EntityHandle<TestEvent>> coll = indexEngine.getIndexedCollection(TestEvent.class);
        TestEvent test = coll.retrieve(equal(TestEvent.ATTR, "test")).uniqueResult().get();
        assertNotNull(test.timestamp());

        IndexedCollection<EntityHandle<RepositoryTestCommand>> coll1 = indexEngine
                .getIndexedCollection(RepositoryTestCommand.class);
        RepositoryTestCommand test1 = coll1.retrieve(equal(RepositoryTestCommand.ATTR, "test")).uniqueResult().get();
        assertNotNull(test1.timestamp());

        assertTrue(test.timestamp().compareTo(test1.timestamp()) > 0);
    }

    @Test
    @SneakyThrows
    public void commandTimestamping() {
        HybridTimestamp timestamp = new HybridTimestamp(timeProvider);
        timestamp.update();
        RepositoryTestCommand command1 = RepositoryTestCommand.builder().value("forced")
                                                              .timestamp(timestamp)
                                                              .build();
        RepositoryTestCommand command2 = RepositoryTestCommand.builder().build();
        IndexedCollection<EntityHandle<RepositoryTestCommand>> coll1 = indexEngine
                .getIndexedCollection(RepositoryTestCommand.class);

        repository.publish(command2).get();
        repository.publish(command1).get();

        RepositoryTestCommand test1 = coll1.retrieve(equal(RepositoryTestCommand.ATTR, "forced")).uniqueResult().get();
        RepositoryTestCommand test2 = coll1.retrieve(equal(RepositoryTestCommand.ATTR, "test")).uniqueResult().get();

        assertTrue(test1.timestamp().compareTo(test2.timestamp()) < 0);
        assertTrue(repository.getTimestamp().compareTo(test1.timestamp()) > 0);
        assertTrue(repository.getTimestamp().compareTo(test2.timestamp()) > 0);
    }

    @ToString
    public static class TimestampingEventCommand extends StandardCommand<Void, String> {

        private final HybridTimestamp eventTimestamp;

        @LayoutConstructor
        public TimestampingEventCommand(HybridTimestamp timestamp) {
            super(timestamp);
            eventTimestamp = null;
        }

        @Builder
        public TimestampingEventCommand(HybridTimestamp timestamp, HybridTimestamp eventTimestamp) {
            super(timestamp);
            this.eventTimestamp = eventTimestamp;
        }


        @Override
        public EventStream<Void> events(Repository repository) {
            return EventStream.of(new Event[]{
                                  TestEvent.builder().string("test").timestamp(eventTimestamp).build(),
                                  TestEvent.builder().string("followup").build()});
        }

        @Override
        public String result() {
            return "hello, world";
        }
    }

    @Test
    @SneakyThrows
    public void eventTimestamping() {
        HybridTimestamp timestamp = new HybridTimestamp(timeProvider);
        timestamp.update();
        TimestampingEventCommand command = TimestampingEventCommand.builder().eventTimestamp(timestamp).build();

        repository.publish(command).get();

        IndexedCollection<EntityHandle<TestEvent>> coll = indexEngine
                .getIndexedCollection(TestEvent.class);

        TestEvent test = coll.retrieve(equal(TestEvent.ATTR, "test")).uniqueResult().get();

        assertTrue(test.timestamp().compareTo(command.timestamp()) < 0);
        assertTrue(repository.getTimestamp().compareTo(test.timestamp()) > 0);

        TestEvent followup = coll.retrieve(equal(TestEvent.ATTR, "followup")).uniqueResult().get();
        assertTrue(test.timestamp().compareTo(followup.timestamp()) < 0);

        assertTrue(repository.getTimestamp().compareTo(followup.timestamp()) > 0);
    }

    @Test
    @SneakyThrows
    public void indexing() {
        repository.publish(RepositoryTestCommand.builder().build()).get();
        IndexedCollection<EntityHandle<TestEvent>> coll = indexEngine.getIndexedCollection(TestEvent.class);
        assertTrue(coll.retrieve(equal(TestEvent.ATTR, "test")).isNotEmpty());
        assertTrue(coll.retrieve(contains(TestEvent.ATTR, "es")).isNotEmpty());
        assertEquals(coll.retrieve(equal(TestEvent.ATTR, "test")).uniqueResult().get().string(), "test");

        IndexedCollection<EntityHandle<RepositoryTestCommand>> coll1 = indexEngine
                .getIndexedCollection(RepositoryTestCommand.class);
        assertTrue(coll1.retrieve(equal(RepositoryTestCommand.ATTR, "test")).isNotEmpty());
        assertTrue(coll1.retrieve(contains(RepositoryTestCommand.ATTR, "es")).isNotEmpty());

    }

    @Test
    @SneakyThrows
    public void publishingNewCommand() {
        assertFalse(repository.getCommands().contains(BogusCommand.class));
        repository.addCommandSetProvider(new PackageCommandSetProvider(new Package[]{BogusCommand.class.getPackage()}));
        repository.addEventSetProvider(new PackageEventSetProvider(new Package[]{BogusEvent.class.getPackage()}));
        assertTrue(repository.getCommands().contains(BogusCommand.class));
        assertTrue(repository.getEvents().contains(BogusEvent.class));
        assertEquals("bogus", repository.publish(BogusCommand.builder().build()).get());
        // testing its indexing
        IndexedCollection<EntityHandle<BogusEvent>> coll = indexEngine.getIndexedCollection(BogusEvent.class);
        assertTrue(coll.retrieve(equal(BogusEvent.ATTR, "bogus")).isNotEmpty());
        assertTrue(coll.retrieve(contains(BogusEvent.ATTR, "us")).isNotEmpty());
        assertEquals(coll.retrieve(equal(BogusEvent.ATTR, "bogus")).uniqueResult().get().string(), "bogus");
    }

    public static class LockCommand extends StandardCommand<Void, Void> {
        @Builder
        public LockCommand(HybridTimestamp timestamp) {
            super(timestamp);
        }

        @Override
        public EventStream<Void> events(Repository repository, LockProvider lockProvider) {
            lockProvider.lock("LOCK");
            return EventStream.empty();
        }
    }

    @Test(timeOut = 1000) @SneakyThrows
    public void lockTracking() {
        repository.publish(LockCommand.builder().build()).get();
        Lock lock = lockProvider.lock("LOCK");
        assertTrue(lock.isLocked());
        lock.unlock();
    }

    public static class ExceptionalLockCommand extends StandardCommand<Void, Void> {
        @Builder
        public ExceptionalLockCommand(HybridTimestamp timestamp) {
            super(timestamp);
        }

        @Override
        public EventStream<Void> events(Repository repository, LockProvider lockProvider) {
            lockProvider.lock("LOCK");
            throw new IllegalStateException();
        }
    }

    @Test(timeOut = 1000) @SneakyThrows
    public void exceptionalLockTracking() {
        repository.publish(ExceptionalLockCommand.builder().build()).exceptionally(throwable -> null).get();
        Lock lock = lockProvider.lock("LOCK");
        assertTrue(lock.isLocked());
        lock.unlock();
    }


    public static class ExceptionalCommand extends StandardCommand<Void, Object> {
        @Builder
        public ExceptionalCommand(HybridTimestamp timestamp) {
            super(timestamp);
        }

        @Override
        public EventStream<Void> events(Repository repository) {
            throw new IllegalStateException();
        }
    }

    @Test @SneakyThrows
    public void exceptionalCommand() {
        ExceptionalCommand command = ExceptionalCommand.builder().build();
        Object o = repository.publish(command).exceptionally(throwable -> throwable).get();
        assertTrue(o instanceof IllegalStateException);
        Optional<Entity> commandLookup = journal.get(command.uuid());
        assertTrue(commandLookup.isPresent());
        ResultSet<EntityHandle<CommandTerminatedExceptionally>> resultSet = repository
                .query(CommandTerminatedExceptionally.class,
                       equal(CommandTerminatedExceptionally.COMMAND_ID, command.uuid()));
        assertEquals(resultSet.size(), 1);
        EntityHandle<CommandTerminatedExceptionally> result = resultSet.uniqueResult();
        assertEquals(result.get().className(), IllegalStateException.class.getName());
    }


    @ToString
    public static class StreamExceptionCommand extends StandardCommand<Void, Void> {

        private final UUID eventUUID;


        @LayoutConstructor
        public StreamExceptionCommand(HybridTimestamp timestamp) {
            super(timestamp);
            eventUUID = null;
        }

        @Builder
        public StreamExceptionCommand(HybridTimestamp timestamp, UUID eventUUID) {
            super(timestamp);
            this.eventUUID = eventUUID == null ? UUID.randomUUID() : eventUUID;
        }


        @Override
        public EventStream<Void> events(Repository repository) {
            return EventStream.of(Stream.concat(Stream.of(
                    TestEvent.builder().string("test").build().uuid(eventUUID)),
                                 Stream.generate(() -> {
                                     throw new IllegalStateException();
                                 })));
        }
    }

    @Test
    @SneakyThrows
    public void streamExceptionIndexing() {
        IndexedCollection<EntityHandle<TestEvent>> coll = indexEngine.getIndexedCollection(TestEvent.class);
        coll.clear();
        UUID eventUUID = UUID.randomUUID();
        StreamExceptionCommand command = StreamExceptionCommand.builder().eventUUID(eventUUID).build();
        CompletableFuture<Void> future = repository.publish(command);
        while (!future.isDone()) { Thread.sleep(10); } // to avoid throwing an exception
        assertTrue(future.isCompletedExceptionally());
        ResultSet<EntityHandle<CommandTerminatedExceptionally>> resultSet = repository
                .query(CommandTerminatedExceptionally.class,
                       equal(CommandTerminatedExceptionally.COMMAND_ID, command.uuid()));
        assertEquals(resultSet.size(), 1);
        EntityHandle<CommandTerminatedExceptionally> result = resultSet.uniqueResult();
        assertEquals(result.get().className(), IllegalStateException.class.getName());
        assertTrue(journal.get(command.uuid()).isPresent());
        assertFalse(journal.get(eventUUID).isPresent());
        assertTrue(repository.query(TestEvent.class, equal(TestEvent.ATTR, "test")).isEmpty());
    }


    public static class StatePassageCommand extends StandardCommand<String, String> {

        @Builder
        public StatePassageCommand(HybridTimestamp timestamp) {
            super(timestamp);
        }

        @Override
        public EventStream<String> events(Repository repository, LockProvider lockProvider) throws Exception {
            return EventStream.empty("hello");
        }

        @Override
        public String result(String state) {
            return state;
        }
    }

    @Test @SneakyThrows
    public void statePassage() {
        String s = repository.publish(StatePassageCommand.builder().build()).get();
        assertEquals(s, "hello");
    }

    public static class PassingLockProvider extends StandardCommand<Boolean, Boolean> {

        @Builder
        public PassingLockProvider(HybridTimestamp timestamp) {
            super(timestamp);
        }

        @Override
        public EventStream<Boolean> events(Repository repository, LockProvider lockProvider) throws Exception {
            return EventStream.empty(lockProvider != null);
        }

        @Override
        public Boolean result(Boolean passed) {
            return passed;
        }
    }

    @Test @SneakyThrows
    public void passingLock() {
        assertTrue(repository.publish(PassingLockProvider.builder().build()).get());
    }

    @Accessors(fluent = true) @ToString
    public static class TestOptionalEvent extends StandardEvent {
        @Getter
        private final Optional<String> optional;

        @Index({IndexEngine.IndexFeature.EQ, IndexEngine.IndexFeature.UNIQUE})
        public static SimpleAttribute<TestOptionalEvent, UUID> ATTR = new SimpleAttribute<TestOptionalEvent, UUID>() {
            @Override
            public UUID getValue(TestOptionalEvent object, QueryOptions queryOptions) {
                return object.uuid();
            }
        };

        @Builder
        public TestOptionalEvent(Optional<String> optional) {
            this.optional = optional;
        }
    }

    @Accessors(fluent = true)
    @ToString
    public static class TestOptionalCommand extends StandardCommand<Void, Void> {
        @Getter
        private final Optional<String> optional;

        @Builder
        public TestOptionalCommand(HybridTimestamp timestamp, Optional<String> optional) {
            super(timestamp);
            this.optional = optional;
        }

        @Override
        public EventStream<Void> events(Repository repository) {
            return EventStream.of(TestOptionalEvent.builder().optional(optional).build());
        }

        @Index({IndexEngine.IndexFeature.EQ, IndexEngine.IndexFeature.UNIQUE})
        public static SimpleAttribute<TestOptionalCommand, UUID> ATTR = new SimpleAttribute<TestOptionalCommand, UUID>() {
            @Override
            public UUID getValue(TestOptionalCommand object, QueryOptions queryOptions) {
                return object.uuid();
            }
        };

    }

    @Test @SneakyThrows
    public void goesThroughLayoutSerialization() {
        TestOptionalCommand command = TestOptionalCommand.builder().build();
        repository.publish(command).get();

        TestOptionalCommand test = repository
                .query(TestOptionalCommand.class, equal(TestOptionalCommand.ATTR, command.uuid())).uniqueResult().get();
        assertFalse(test.optional().isPresent());

        TestOptionalEvent testOptionalEvent = repository.query(TestOptionalEvent.class, all(TestOptionalEvent.class))
                                                        .uniqueResult().get();
        assertFalse(testOptionalEvent.optional().isPresent());
    }

    @Test @SneakyThrows
    public void causalRelationship() {
        RepositoryTestCommand command = RepositoryTestCommand.builder().build();
        repository.publish(command).get();
        try (ResultSet<EntityHandle<EventCausalityEstablished>> resultSet = repository
                .query(EventCausalityEstablished.class, equal(EventCausalityEstablished.COMMAND, command.uuid()))) {
            assertEquals(resultSet.size(), 1);
        }
    }

}