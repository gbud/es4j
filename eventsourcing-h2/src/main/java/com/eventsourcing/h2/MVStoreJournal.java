/**
 * Copyright (c) 2016, All Contributors (see CONTRIBUTORS file)
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.eventsourcing.h2;

import com.eventsourcing.*;
import com.eventsourcing.events.CommandTerminatedExceptionally;
import com.eventsourcing.events.EventCausalityEstablished;
import com.eventsourcing.hlc.HybridTimestamp;
import com.eventsourcing.layout.ObjectDeserializer;
import com.eventsourcing.layout.ObjectSerializer;
import com.eventsourcing.layout.Serialization;
import com.eventsourcing.layout.binary.BinarySerialization;
import com.eventsourcing.layout.Layout;
import com.eventsourcing.repository.Journal;
import com.eventsourcing.repository.JournalEntityHandle;
import com.eventsourcing.repository.JournalMBean;
import com.eventsourcing.repository.LockProvider;
import com.google.common.base.Joiner;
import com.google.common.collect.Iterators;
import com.google.common.io.BaseEncoding;
import com.google.common.primitives.Bytes;
import com.google.common.util.concurrent.AbstractService;
import com.googlecode.cqengine.index.support.CloseableIterator;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.db.TransactionStore;
import org.h2.mvstore.db.TransactionStore.TransactionMap;
import org.h2.mvstore.type.ObjectDataType;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;

import javax.management.openmbean.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component(
        property = {"filename=journal.db", "type=MVStoreJournal", "jmx.objectname=com.eventsourcing:type=journal,name=MVStoreJournal"})
@Slf4j
public class MVStoreJournal extends AbstractService implements Journal, JournalMBean {
    private Repository repository;

    @Getter(AccessLevel.PACKAGE) @Setter(AccessLevel.PACKAGE) // getter and setter for tests
    private MVStore store;
    private TransactionMap<UUID, ByteBuffer> commandPayloads;
    private TransactionMap<UUID, byte[]> commandHashes;
    private TransactionMap<byte[], Boolean> hashCommands;
    private TransactionMap<UUID, ByteBuffer> eventPayloads;
    private TransactionMap<byte[], Boolean> hashEvents;
    private TransactionMap<UUID, byte[]> eventHashes;

    private MVMap<byte[], byte[]> layouts;
    private TransactionStore transactionStore;
    TransactionStore.Transaction readTx;

    public MVStoreJournal(MVStore store) {
        this();
        this.store = store;
    }

    private final static Serialization serialization = BinarySerialization.getInstance();

    @SneakyThrows
    public MVStoreJournal() {
        layoutInformationLayout = Layout.forClass(LayoutInformation.class);
        layoutInformationSerializer = serialization.getSerializer(LayoutInformation.class);
        layoutInformationDeserializer = serialization.getDeserializer(LayoutInformation.class);
    }

    @Activate
    protected void activate(ComponentContext ctx) {
        store = MVStore.open((String) ctx.getProperties().get("filename"));
    }

    @Deactivate
    protected void deactivate(ComponentContext ctx) {
        store.close();
    }

    @Override
    protected void doStart() {
        if (repository == null) {
            notifyFailed(new IllegalStateException("repository == null"));
        }

        if (store == null) {
            notifyFailed(new IllegalStateException("store == null"));
        }

        initializeStore();

        repository.getCommands().forEach(new EntityLayoutExtractor());
        repository.getEvents().forEach(new EntityLayoutExtractor());

        notifyStarted();
    }

    @Override
    public void onCommandsAdded(Set<Class<? extends Command>> commands) {
        commands.forEach(new EntityLayoutExtractor());
        reportUnrecognizedEntities();
    }

    @Override
    public void onEventsAdded(Set<Class<? extends Event>> events) {
        events.forEach(new EntityLayoutExtractor());
        reportUnrecognizedEntities();
    }

    void reportUnrecognizedEntities() {
        getUnrecognizedEntities().forEach(layoutInformation -> {
            String properties = Joiner.on("\n  ").join(layoutInformation.properties().stream().
                    map(propertyInformation ->
                                propertyInformation.name() + ": " + propertyInformation.type()).
                                                                                collect(Collectors.toList()));
            log.warn("Unrecognized entity {} (hash: {})\nProperties:\n  {}\n",
                     layoutInformation.className(), BaseEncoding.base16().encode(layoutInformation.hash()), properties);
        });
    }

    public List<LayoutInformation> getUnrecognizedEntities() {
        List<LayoutInformation> result = new ArrayList<>();
        layouts.forEach(new BiConsumer<byte[], byte[]>() {
            @Override
            public void accept(byte[] hash, byte[] info) {
                if (!layoutsByHash.containsKey(BaseEncoding.base16().encode(hash))) {
                    result.add(layoutInformationDeserializer.deserialize(ByteBuffer.wrap(info)));
                }
            }
        });
        return result;
    }


    @Override
    public String getName() {
        return store.getFileStore().getFileName();
    }

    @Override @SneakyThrows
    public TabularData getEntities() {
        CompositeType propertyType = new CompositeType("Entity Property", "Entity Property",
                                                       new String[]{"Name", "Type"}, new String[]{"Name", "Type"},
                                                       new OpenType[]{SimpleType.STRING, SimpleType.STRING});
        TabularType propertyTabular = new TabularType("Property", "Property", propertyType, new String[]{"Name"});
        CompositeType compositeType = new CompositeType("Entity", "Entity",
                                                        new String[]{"Name", "Hash", "Recognized", "Properties"},
                                                        new String[]{"Name", "Hash", "Recognized", "Properties"},
                                                        new OpenType[]{SimpleType.STRING, SimpleType.STRING, SimpleType.BOOLEAN, propertyTabular});
        TabularDataSupport tabular = new TabularDataSupport(
                new TabularType("Entity", "Journalled entity", compositeType, new String[]{"Name"}));
        layouts.forEach(new BiConsumer<byte[], byte[]>() {
            @Override @SneakyThrows
            public void accept(byte[] hash, byte[] info) {
                LayoutInformation layoutInformation = layoutInformationDeserializer.deserialize(ByteBuffer.wrap(info));
                boolean recognized = layoutsByHash.containsKey(BaseEncoding.base16().encode(hash));
                Map<String, Object> entity = new HashMap<>();
                entity.put("Name", layoutInformation.className());
                entity.put("Hash", BaseEncoding.base16().encode(layoutInformation.hash()));
                entity.put("Recognized", recognized);
                TabularDataSupport propertyTab = new TabularDataSupport(propertyTabular);
                layoutInformation.properties().forEach(new Consumer<PropertyInformation>() {
                    @Override @SneakyThrows
                    public void accept(PropertyInformation propertyInformation) {
                        propertyTab.put(new CompositeDataSupport(propertyType, new String[]{"Name", "Type"},
                                                                 new Object[]{propertyInformation.name(), propertyInformation.type()}));
                    }
                });
                entity.put("Properties", propertyTab);
                tabular.put(new CompositeDataSupport(compositeType, entity));
            }
        });
        return tabular;
    }

    void initializeStore() {
        MVMap<String, Object> info = store.openMap("info");
        info.putIfAbsent("version", 1);
        store.commit();

        transactionStore = new TransactionStore(this.store);
        transactionStore.init();

        readTx = transactionStore.begin();
        commandPayloads = readTx.openMap("commandPayloads", new ObjectDataType(), new ByteBufferDataType());
        commandHashes = readTx.openMap("commandHashes");
        hashCommands = readTx.openMap("hashCommands");
        eventPayloads = readTx.openMap("eventPayloads", new ObjectDataType(), new ByteBufferDataType());
        eventHashes = readTx.openMap("eventHashes");
        hashEvents = readTx.openMap("hashEvents");

        layouts = store.openMap("layouts");
    }

    @Override
    protected void doStop() {
        transactionStore.close();
        store.close();
        notifyStopped();
    }

    private Map<String, Layout> layoutsByHash = new HashMap<>();
    private Map<String, Layout> layoutsByClass = new HashMap<>();

    @Override
    public void setRepository(Repository repository) {
        this.repository = repository;
    }

    @Override @SuppressWarnings("unchecked")
    public long journal(Command<?, ?> command, Journal.Listener listener, LockProvider lockProvider) throws Exception {
        return journal(command, listener, lockProvider, null);
    }

    private long journal(Command<?, ?> command, Journal.Listener listener, LockProvider lockProvider, Stream<? extends
            Event> events)
            throws Exception {
        TransactionStore.Transaction tx = transactionStore.begin();
        try {
            Layout commandLayout = layoutsByClass.get(command.getClass().getName());

            ByteBuffer hashBuffer = ByteBuffer.allocate(16 + 20); // based on SHA-1
            hashBuffer.put(commandLayout.getHash());
            hashBuffer.putLong(command.uuid().getMostSignificantBits());
            hashBuffer.putLong(command.uuid().getLeastSignificantBits());

            TransactionMap<UUID, ByteBuffer> txCommandPayloads = tx.openMap("commandPayloads", new ObjectDataType(),
                                                                            new ByteBufferDataType());
            TransactionMap<byte[], Boolean> txHashCommands = tx.openMap("hashCommands");
            TransactionMap<UUID, byte[]> txCommandHashes = tx.openMap("commandHashes");

            Stream<? extends Event> actualEvents;

            if (events == null) {
                EventStream<?> eventStream = command.events(repository, lockProvider);
                listener.onCommandStateReceived(eventStream.getState());
                actualEvents = eventStream.getStream();
            } else {
                actualEvents = events;
            }

            EventConsumer eventConsumer = new EventConsumer(tx, command, listener);
            long count = actualEvents.peek(new Consumer<Event>() {
                @Override public void accept(Event event) {
                    eventConsumer.accept(event);
                    eventConsumer.accept(EventCausalityEstablished.builder()
                                                                  .event(event.uuid())
                                                                  .command(command.uuid())
                                                                  .build());
                }
            }).count();

            ByteBuffer buffer = serialization.getSerializer(command.getClass()).serialize(command);
            buffer.rewind();
            txCommandPayloads.tryPut(command.uuid(), buffer);
            txHashCommands.tryPut(hashBuffer.array(), true);
            txCommandHashes.tryPut(command.uuid(), commandLayout.getHash());

            tx.prepare();
            tx.commit();

            listener.onCommit();
            
            return count;
        } catch (Exception e) {
            tx.rollback();
            listener.onAbort(e);


            // if we are having an exception NOT when journalling CommandTerminatedExceptionally
            if (events == null) {
                journal(command, listener, lockProvider,
                        Stream.of((Event) new CommandTerminatedExceptionally(command.uuid(), e)));
            }

            throw e;
        }

    }

    @Override
    @SneakyThrows @SuppressWarnings("unchecked")
    public <T extends Entity> Optional<T> get(UUID uuid) {
        if (commandPayloads.containsKey(uuid)) {
            ByteBuffer payload = commandPayloads.get(uuid);
            payload.rewind();
            String encodedHash = BaseEncoding.base16().encode(commandHashes.get(uuid));
            Layout<Command<?, ?>> layout = layoutsByHash.get(encodedHash);
            Command command = (Command) serialization.getDeserializer(layout.getLayoutClass()).deserialize(payload);
            command.uuid(uuid);
            return Optional.of((T) command);
        }
        if (eventPayloads.containsKey(uuid)) {
            ByteBuffer payload = eventPayloads.get(uuid);
            payload.rewind();
            String encodedHash = BaseEncoding.base16().encode(eventHashes.get(uuid));
            Layout<Event> layout = layoutsByHash.get(encodedHash);
            Event event = (Event) serialization.getDeserializer(layout.getLayoutClass()).deserialize(payload);
            event.uuid(uuid);
            return Optional.of((T) event);
        }
        return Optional.empty();

    }

    @Override
    public <T extends Command<?, ?>> CloseableIterator<EntityHandle<T>> commandIterator(Class<T> klass) {
        Layout layout = layoutsByClass.get(klass.getName());
        byte[] hash = layout.getHash();
        Iterator<Map.Entry<byte[], Boolean>> iterator = hashCommands.entryIterator(hashCommands.higherKey(hash));
        return new EntityHandleIterator<>(iterator, bytes -> Bytes.indexOf(bytes, hash) == 0,
                                          new EntityFunction<>(hash));
    }

    @Override
    public <T extends Event> CloseableIterator<EntityHandle<T>> eventIterator(Class<T> klass) {
        Layout layout = layoutsByClass.get(klass.getName());
        byte[] hash = layout.getHash();
        Iterator<Map.Entry<byte[], Boolean>> iterator = hashEvents.entryIterator(hashEvents.higherKey(hash));
        return new EntityHandleIterator<>(iterator, bytes -> Bytes.indexOf(bytes, hash) == 0,
                                          new EntityFunction<>(hash));
    }

    @Override
    public void clear() {
        commandPayloads.clear();
        hashCommands.clear();
        eventPayloads.clear();
        eventHashes.clear();
        hashEvents.clear();
        layouts.clear();
    }

    @Override @SuppressWarnings("unchecked")
    public <T extends Entity> long size(Class<T> klass) {
        if (Event.class.isAssignableFrom(klass)) {
            return Iterators.size(eventIterator((Class<Event>) klass));
        }
        if (Command.class.isAssignableFrom(klass)) {
            return Iterators.size(commandIterator((Class<Command<?, ?>>) klass));
        }
        throw new IllegalArgumentException();
    }

    @Override
    public <T extends Entity> boolean isEmpty(Class<T> klass) {
        if (Event.class.isAssignableFrom(klass)) {
            return !eventIterator((Class<Event>) klass).hasNext();
        }
        if (Command.class.isAssignableFrom(klass)) {
            return !commandIterator((Class<Command<?, ?>>) klass).hasNext();
        }
        throw new IllegalArgumentException();
    }

    @Accessors(fluent = true)
    public static class PropertyInformation {
        @Getter
        private final String name;

        @Getter
        private final String type;

        public PropertyInformation(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }

    @Accessors(fluent = true)
    public static class LayoutInformation {
        @Getter
        private final byte[] hash;

        @Getter
        private final String className;
        @Getter
        private final List<PropertyInformation> properties;

        public LayoutInformation(byte[] hash, String className,
                                 List<PropertyInformation> properties) {
            this.hash = hash;
            this.className = className;
            this.properties = properties;
        }
    }

    private final Layout<LayoutInformation> layoutInformationLayout;
    private final ObjectSerializer<LayoutInformation> layoutInformationSerializer;
    private final ObjectDeserializer<LayoutInformation> layoutInformationDeserializer;


    private class EntityLayoutExtractor implements Consumer<Class<? extends Entity>> {

        @Override
        @SneakyThrows
        public void accept(Class<? extends Entity> aClass) {
            Layout<? extends Entity> layout = Layout.forClass(aClass);
            byte[] hash = layout.getHash();
            String encodedHash = BaseEncoding.base16().encode(hash);
            layoutsByHash.put(encodedHash, layout);
            layoutsByClass.put(aClass.getName(), layout);

            List<PropertyInformation> properties = layout.getProperties().stream()
                    .map(property -> new PropertyInformation(property.getName(), property.getType()
                                                                                        .getBriefDescription()))
                     .collect(Collectors.toList());

            LayoutInformation layoutInformation = new LayoutInformation(hash, aClass.getName(), properties);
            layouts.put(hash, layoutInformationSerializer.serialize(layoutInformation).array());
        }

    }

    static private class EntityHandleIterator<K, V, R> implements CloseableIterator<R> {

        private final Iterator<Map.Entry<K, V>> iterator;
        private Function<K, Boolean> hasNext;
        private final BiFunction<K, V, R> function;
        private Map.Entry<K, V> entry;

        public EntityHandleIterator(Iterator<Map.Entry<K, V>> iterator, Function<K, Boolean> hasNext,
                                    BiFunction<K, V, R> function) {
            this.iterator = iterator;
            this.hasNext = hasNext;
            this.function = function;
        }

        @Override
        public boolean hasNext() {
            if (iterator.hasNext()) {
                entry = iterator.next();
                return hasNext.apply(entry.getKey());
            } else {
                return false;
            }
        }

        @Override
        public R next() {
            if (entry == null) {
                entry = iterator.next();
            }
            R result = function.apply(entry.getKey(), entry.getValue());
            entry = null;
            return result;
        }

        @Override
        public void close() {

        }
    }

    private class EntityFunction<T extends Entity, V> implements BiFunction<byte[], V, EntityHandle<T>> {
        private final byte[] hash;

        public EntityFunction(byte[] hash) {
            this.hash = hash;
        }

        @Override
        public EntityHandle<T> apply(byte[] bytes, V value) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            UUID uuid = new UUID(buffer.getLong(hash.length), buffer.getLong(hash.length + 8));
            return new JournalEntityHandle<>(MVStoreJournal.this, uuid);
        }
    }

    private class EventConsumer implements Consumer<Event> {
        private final HybridTimestamp ts;
        private final TransactionStore.Transaction tx;
        private final Command<?, ?> command;
        private final Journal.Listener listener;
        private final TransactionMap<UUID, byte[]> txEventHashes;
        private final TransactionMap<byte[], Boolean> txHashEvents;
        private final TransactionMap<UUID, ByteBuffer> txEventPayloads;

        public EventConsumer(TransactionStore.Transaction tx, Command<?, ?> command, Journal.Listener listener) {
            this.tx = tx;
            this.command = command;
            this.listener = listener;
            this.ts = command.timestamp().clone();
            txEventPayloads = tx.openMap("eventPayloads", new ObjectDataType(), new ByteBufferDataType());
            txHashEvents = tx.openMap("hashEvents");
            txEventHashes = tx.openMap("eventHashes");
        }

        @Override
        @SneakyThrows
        public void accept(Event event) {

            if (event.timestamp() == null) {
                ts.update();
                event.timestamp(ts.clone());
            } else {
                ts.update(event.timestamp().clone());
            }

            Layout layout = layoutsByClass.get(event.getClass().getName());

            ObjectSerializer serializer = serialization.getSerializer(event.getClass());
            int size = serializer.size(event);

            ByteBuffer payloadBuffer = ByteBuffer.allocate(size);
            serializer.serialize(event, payloadBuffer);
            payloadBuffer.rewind();

            txEventPayloads.tryPut(event.uuid(), payloadBuffer);

            ByteBuffer hashBuffer = ByteBuffer.allocate(20 + 16); // Based on SHA-1

            hashBuffer.rewind();
            hashBuffer.put(layout.getHash());
            hashBuffer.putLong(event.uuid().getMostSignificantBits());
            hashBuffer.putLong(event.uuid().getLeastSignificantBits());


            txHashEvents.tryPut(hashBuffer.array(), true);
            txEventHashes.tryPut(event.uuid(), layout.getHash());

            listener.onEvent(event);
        }
    }
}
