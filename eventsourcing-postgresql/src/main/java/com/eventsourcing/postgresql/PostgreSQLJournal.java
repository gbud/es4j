/**
 * Copyright (c) 2016, All Contributors (see CONTRIBUTORS file)
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.eventsourcing.postgresql;

import com.eventsourcing.*;
import com.eventsourcing.events.CommandTerminatedExceptionally;
import com.eventsourcing.events.EventCausalityEstablished;
import com.eventsourcing.hlc.HybridTimestamp;
import com.eventsourcing.layout.Layout;
import com.eventsourcing.layout.Property;
import com.eventsourcing.layout.TypeHandler;
import com.eventsourcing.layout.types.*;
import com.eventsourcing.repository.Journal;
import com.eventsourcing.repository.JournalEntityHandle;
import com.eventsourcing.repository.LockProvider;
import com.google.common.base.Joiner;
import com.google.common.io.BaseEncoding;
import com.google.common.util.concurrent.AbstractService;
import com.googlecode.cqengine.index.support.CloseableIterator;
import lombok.Setter;
import lombok.SneakyThrows;
import org.flywaydb.core.Flyway;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import javax.sql.DataSource;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Component(property = "type=PostgreSQLJournal")
public class PostgreSQLJournal extends AbstractService implements Journal {

    @Reference
    protected DataSourceProvider dataSourceProvider;

    private DataSource dataSource;

    @Setter
    private Repository repository;

    @Activate
    protected void activate() {
        dataSource = dataSourceProvider.getDataSource();
    }

    public PostgreSQLJournal() {}

    public PostgreSQLJournal(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override public void onCommandsAdded(Set<Class<? extends Command>> commands) {
        commands.forEach(new LayoutExtractor());
    }

    @Override public void onEventsAdded(Set<Class<? extends Event>> events) {
        events.forEach(new LayoutExtractor());
    }

    @Override @SuppressWarnings("unchecked")
    public long journal(Command<?, ?> command, Journal.Listener listener, LockProvider lockProvider) throws Exception {
        return journal(command, listener, lockProvider, null);
    }

    private long journal(Command<?, ?> command, Journal.Listener listener, LockProvider lockProvider, Stream<? extends
            Event> events)
            throws Exception {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        Savepoint savepoint = connection.setSavepoint();
        try {

            Stream<? extends Event> actualEvents;

            if (events == null) {
                EventStream<?> eventStream = command.events(repository, lockProvider);
                listener.onCommandStateReceived(eventStream.getState());
                actualEvents = eventStream.getStream();
            } else {
                actualEvents = events;
            }

            EventConsumer eventConsumer = new EventConsumer(connection, command, listener);
            long count = actualEvents.peek(new Consumer<Event>() {
                @Override public void accept(Event event) {
                    eventConsumer.accept(event);
                    eventConsumer.accept(EventCausalityEstablished.builder()
                                                                  .event(event.uuid())
                                                                  .command(command.uuid())
                                                                  .build());
                }
            }).count();

            Layout layout = layoutsByClass.get(command.getClass().getName());
            String encoded = BaseEncoding.base16().encode(layout.getHash());
            insertFunctions.get(encoded).apply(command, connection);

            connection.releaseSavepoint(savepoint);
            connection.commit();
            connection.close();

            listener.onCommit();

            return count;
        } catch (Exception e) {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
            listener.onAbort(e);
            connection.close();

            // if we are having an exception NOT when journalling CommandTerminatedExceptionally
            if (events == null) {
                journal(command, listener, lockProvider,
                        Stream.of(new CommandTerminatedExceptionally(command.uuid(), e)));
            }

            throw e;
        }

    }



    private String getParameter(Property p, boolean topLevel) {
        if (p.getTypeHandler() instanceof ObjectTypeHandler) {
            List<? extends Property<?>> ps = ((ObjectTypeHandler) p
                    .getTypeHandler())
                    .getLayout().getProperties();
            return
                    Joiner.on(", ").join(
                            ps.stream()
                              .map(px -> (topLevel ? "(" : "") + "\"" + p.getName() + "\"" +
                                         (topLevel ? ")" : "") + "." + getParameter(px, false) + "")
                              .collect(Collectors.toList()));
        } else
        if (p.getTypeHandler() instanceof ListTypeHandler &&
                ((ListTypeHandler) p.getTypeHandler()).getWrappedHandler() instanceof ObjectTypeHandler) {
            ObjectTypeHandler handler = (ObjectTypeHandler) ((ListTypeHandler) p.getTypeHandler()).getWrappedHandler();
            List<? extends Property<?>> ps = handler.getLayout().getProperties();
            return Joiner.on(", ").join(
                ps.stream()
                  .map(px -> "(SELECT array_agg((i).\"" + px.getName() + "\") FROM unnest(" + p.getName() + ") AS i)")
                  .collect(Collectors.toList()));
        } else {
           return "\"" + p.getName() + "\"";
        }
    }

    @SneakyThrows
    @Override public <T extends Entity> Optional<T> get(UUID uuid) {
            Optional<T> result;
        Connection connection = dataSource.getConnection();
        PreparedStatement s = connection
                .prepareStatement("SELECT layout FROM eventsourcing.layouts WHERE uuid = ?::UUID");
        s.setString(1, uuid.toString());
        try (ResultSet resultSet = s.executeQuery()) {
            if (resultSet.next()) {
                String hash = BaseEncoding.base16().encode(resultSet.getBytes(1));
                ReaderFunction reader = readerFunctions.get(hash);
                Layout<?> layout = layoutsByHash.get(hash);
                List<? extends Property<?>> properties = layout.getProperties();
                String columns = Joiner.on(", ").join(properties.stream()
                                                            .map(p -> getParameter(p, true)).collect(Collectors.toList
                                ()));
                String query = "SELECT " + columns + " FROM layout_" + hash + " WHERE uuid = ?::UUID";

                PreparedStatement s1 = connection.prepareStatement(query);
                s1.setString(1, uuid.toString());

                try (ResultSet rs = s1.executeQuery()) {
                    rs.next();
                    Entity o = (Entity) reader.apply(rs);
                    o.uuid(uuid);
                    result = Optional.of((T) o);
                }
                s1.close();
            } else {
                result = Optional.empty();
            }
        }
        s.close();
        connection.close();
        return result;
    }

    @Override public <T extends Command<?, ?>> CloseableIterator<EntityHandle<T>> commandIterator(Class<T> klass) {
        return entityIterator(klass);
    }

    @Override public <T extends Event> CloseableIterator<EntityHandle<T>> eventIterator(Class<T> klass) {
        return entityIterator(klass);
    }

    @SneakyThrows
    private <T extends Entity> CloseableIterator<EntityHandle<T>> entityIterator(Class<T> klass) {
        Connection connection = dataSource.getConnection();

        Layout layout = layoutsByClass.get(klass.getName());
        String hash = BaseEncoding.base16().encode(layout.getHash());

        PreparedStatement s = connection.prepareStatement("SELECT uuid FROM layout_" + hash);
        return new EntityIterator<>(this, s, connection);
    }

    static private class EntityIterator<R extends Entity> implements CloseableIterator<EntityHandle<R>> {


        private final Journal journal;
        private ResultSet resultSet;
        private final PreparedStatement statement;
        private final Connection connection;

        public EntityIterator(Journal journal, PreparedStatement statement,
                              Connection connection) {
            this.journal = journal;
            this.statement = statement;
            this.connection = connection;
        }

        @SneakyThrows
        @Override
        public boolean hasNext() {

            // lazy query execution
            if (resultSet == null) {
                resultSet = statement.executeQuery();
            }

            if (resultSet.next()) {
                return true;
            } else {
                close();
                return false;
            }
        }

        @SneakyThrows
        @Override
        public EntityHandle<R> next() {
            return new JournalEntityHandle<>(journal, UUID.fromString(resultSet.getString(1)));
        }

        @SneakyThrows
        @Override
        public void close() {
            if (!resultSet.isClosed()) resultSet.close();
            if (!statement.isClosed()) statement.close();
            if (!statement.isClosed()) connection.close();
        }

    }


    @SneakyThrows
    @Override public void clear() {
        Connection connection = dataSource.getConnection();
        layoutsByHash.keySet().forEach(new Consumer<String>() {
            @SneakyThrows
            @Override public void accept(String hash) {
                PreparedStatement s = connection.prepareStatement("DELETE FROM layout_" + hash);
                s.execute();
                s.close();
            }
        });
        PreparedStatement check = connection
                .prepareStatement("SELECT * from pg_catalog.pg_tables WHERE tablename = 'layouts' AND schemaname = ?");
        check.setString(1, connection.getSchema());
        try (ResultSet resultSet = check.executeQuery()) {
            if (resultSet.next()) {
                PreparedStatement s = connection.prepareStatement("DELETE FROM eventsourcing.layouts");
                s.execute();
                s.close();
            }
        }
        check.close();
        connection.close();
    }

    @SneakyThrows
    @Override public <T extends Entity> long size(Class<T> klass) {
        Layout layout = layoutsByClass.get(klass.getName());
        String hash = BaseEncoding.base16().encode(layout.getHash());
        Connection connection = dataSource.getConnection();
        PreparedStatement s = connection
                .prepareStatement("SELECT count(uuid) FROM layout_" + hash);

        long size;
        try (ResultSet resultSet = s.executeQuery()) {
            resultSet.next();
            size = resultSet.getLong(1);
        }

        s.close();
        connection.close();
        return size;
    }

    @Override public <T extends Entity> boolean isEmpty(Class<T> klass) {
        return size(klass) == 0;
    }

    @Override protected void doStart() {
        if (repository == null) {
            notifyFailed(new IllegalStateException("repository == null"));
        }

        if (dataSource == null) {
            notifyFailed(new IllegalStateException("dataSource == null"));
        }

        repository.getCommands().forEach(new LayoutExtractor());
        repository.getEvents().forEach(new LayoutExtractor());

        ensureLatestSchemaVersion();

        notifyStarted();
    }

    private void ensureLatestSchemaVersion() {
        Flyway flyway = new Flyway();
        flyway.setClassLoader(getClass().getClassLoader());
        flyway.setLocations("com/eventsourcing/postgresql/migrations");
        flyway.setDataSource(dataSource);
        flyway.setSchemas("eventsourcing");
        flyway.migrate();
    }

    @Override protected void doStop() {
        notifyStopped();
    }

    private Map<String, InsertFunction> insertFunctions = new HashMap<>();
    private Map<String, ReaderFunction> readerFunctions = new HashMap<>();

    private class ReaderFunction implements Function<ResultSet, Object> {

        private final Layout layout;

        public ReaderFunction(Layout<?> layout) {
            this.layout = layout;
        }

        @SneakyThrows
        @Override public Object apply(ResultSet resultSet) {
            AtomicInteger i = new AtomicInteger(1);
            List<? extends Property<?>> properties = layout.getProperties();
            Map<Property<?>, Object> props = new HashMap<>();
            for (Property property : properties) {
                TypeHandler typeHandler = property.getTypeHandler();
                props.put(property, getValue(resultSet, i, typeHandler));
            }

            return layout.instantiate(props);
        }

        @SneakyThrows
        private Object getValue(ResultSet resultSet, AtomicInteger i, TypeHandler typeHandler) {
            if (typeHandler instanceof BigDecimalTypeHandler) {
                return resultSet.getBigDecimal(i.getAndIncrement());
            }
            if (typeHandler instanceof BooleanTypeHandler) {
                return resultSet.getBoolean(i.getAndIncrement());
            }
            if (typeHandler instanceof ByteArrayTypeHandler) {
                byte[] bytes = resultSet.getBytes(i.getAndIncrement());
                if (((ByteArrayTypeHandler) typeHandler).isPrimitive()) {
                    return bytes;
                } else {
                    return ((ByteArrayTypeHandler) typeHandler).toObject(bytes);
                }
            }
            if (typeHandler instanceof ByteTypeHandler) {
                return resultSet.getByte(i.getAndIncrement());
            }
            if (typeHandler instanceof DateTypeHandler) {
                return Date.from(resultSet.getTimestamp(i.getAndIncrement()).toInstant());
            }
            if (typeHandler instanceof DoubleTypeHandler) {
                return resultSet.getDouble(i.getAndIncrement());
            }
            if (typeHandler instanceof EnumTypeHandler) {
                Class<? extends Enum> enumClass = ((EnumTypeHandler) typeHandler).getEnumClass();
                String[] enumNames = Arrays.stream(enumClass.getEnumConstants()).map(Enum::name).toArray(String[]::new);
                return Enum.valueOf(enumClass, enumNames[resultSet.getInt(i.getAndIncrement())]);
            }
            if (typeHandler instanceof FloatTypeHandler) {
                return resultSet.getFloat(i.getAndIncrement());
            }
            if (typeHandler instanceof IntegerTypeHandler) {
                return resultSet.getInt(i.getAndIncrement());
            }
            if (typeHandler instanceof ListTypeHandler) {
                if (((ListTypeHandler) typeHandler).getWrappedHandler() instanceof ObjectTypeHandler) {
                    ObjectTypeHandler handler = (ObjectTypeHandler) ((ListTypeHandler) typeHandler).getWrappedHandler();
                    Layout<?> objectLayout = handler.getLayout();
                    List<? extends Property<?>> properties = objectLayout.getProperties();
                    List<Map<String, Object>> list = new ArrayList();
                    for (Property p : properties) {
                        Array array = resultSet.getArray(i.getAndIncrement());
                        ResultSet arrayResultSet = array.getResultSet();
                        int j=0;
                        while (arrayResultSet.next()) {
                            j++;
                            if (list.size() < j) {
                                list.add(new HashMap<>());
                            }
                            Map<String, Object> o = list.get(j - 1);
                            o.put(p.getName(), getValue(arrayResultSet, new AtomicInteger(2), p.getTypeHandler()));
                        }
                    }
                    return list.stream().map(new ObjectArrayCollector(objectLayout, properties)).collect(Collectors.toList());
                } else {
                    Array array = resultSet.getArray(i.getAndIncrement());
                    ResultSet arrayResultSet = array.getResultSet();
                    List list = new ArrayList();
                    TypeHandler handler = ((ListTypeHandler) typeHandler).getWrappedHandler();
                    while (arrayResultSet.next()) {
                        list.add(getValue(arrayResultSet, new AtomicInteger(2), handler));
                    }
                    return list;
                }
            }
            if (typeHandler instanceof LongTypeHandler) {
                return resultSet.getLong(i.getAndIncrement());
            }
            if (typeHandler instanceof ObjectTypeHandler) {
                Layout<?> layout = ((ObjectTypeHandler) typeHandler).getLayout();
                List<? extends Property<?>> properties = layout.getProperties();

                Map<Property<?>, Object> props = new HashMap<>();
                for (Property property : properties) {
                    props.put(property, getValue(resultSet, i, property.getTypeHandler()));
                }
                @SuppressWarnings("unchecked")
                Object o = ((Layout) layout).instantiate(props);
                return o;
            }
            if (typeHandler instanceof OptionalTypeHandler) {
                return Optional.ofNullable(getValue(resultSet, i, ((OptionalTypeHandler) typeHandler)
                        .getWrappedHandler()));
            }
            if (typeHandler instanceof ShortTypeHandler) {
                return resultSet.getShort(i.getAndIncrement());
            }
            if (typeHandler instanceof StringTypeHandler) {
                return resultSet.getString(i.getAndIncrement());
            }
            if (typeHandler instanceof UUIDTypeHandler) {
                return UUID.fromString(resultSet.getString(i.getAndIncrement()));
            }
            throw new RuntimeException("Unsupported type handler " + typeHandler.getClass());
        }

    }

    private static class ObjectArrayCollector implements Function<Map<String,Object>, Object> {
        private final Layout objectLayout;
        private final List<? extends Property<?>> properties;

        public ObjectArrayCollector(Layout<?> objectLayout, List<? extends Property<?>> properties) {
            this.objectLayout = objectLayout;
            this.properties = properties;
        }

        @SneakyThrows
        @Override public Object apply(Map<String, Object> map) {
            Map<Property, Object> props = new HashMap<>();
            for (Property property : properties) {
                props.put(property, map.get(property.getName()));
            }
            @SuppressWarnings("unchecked")
            Object o = objectLayout.instantiate(props);
            return o;
        }
    }

    private class InsertFunction implements BiFunction<Object, Connection, UUID> {
        private final Layout<?> layout;
        private final String table;
        private final List<? extends Property> properties;

        public InsertFunction(Layout<?> layout) {
            this.layout = layout;
            table = "layout_" +  BaseEncoding.base16().encode(layout.getHash());
            properties = layout.getProperties();
        }

        @SneakyThrows
        private String getParameter(Connection connection, TypeHandler typeHandler, Object object) {
            if (typeHandler instanceof UUIDTypeHandler) {
                return "?::UUID";
            } else if (typeHandler instanceof ObjectTypeHandler) {
                Layout layout = ((ObjectTypeHandler) typeHandler).getLayout();
                final Object o = object == null ?
                        layout.instantiate() : object;
                @SuppressWarnings("unchecked")
                List<? extends Property> properties = layout.getProperties();
                @SuppressWarnings("unchecked")
                String rowParameters = Joiner.on(",").join(
                        properties.stream().map(p1 -> getParameter(connection, p1.getTypeHandler(), p1.get(o))
                ).collect(Collectors.toList()));
                return "ROW(" + rowParameters + ")";
            } else if (typeHandler instanceof ListTypeHandler) {
                TypeHandler handler = ((ListTypeHandler) typeHandler).getWrappedHandler();
                List<?> list = object == null ? Arrays.asList() : (List<?>) object;
                String listParameters = Joiner.on(",").join(
                        list.stream().map(i -> getParameter(connection, handler, i))
                                          .collect(Collectors.toList()));
                return "ARRAY[" + listParameters + "]::" + getMappedType(connection, handler) + "[]";
            } else {
                return "?";
            }
        }

        @SneakyThrows
        @Override public UUID apply(Object object, Connection connection) {
            String parameters = Joiner.on(",")
                               .join(properties.stream()
                                               .map(p -> getParameter(connection, p.getTypeHandler(), p.get(object)))
                                               .collect
                                               (Collectors.toList()));

            PreparedStatement s = connection
                    .prepareStatement("INSERT INTO " + table + " VALUES (?::UUID," + parameters + ")");
            int i = 1;
            UUID uuid;
            if (object instanceof Entity) {
                uuid = ((Entity) object).uuid();
            } else {
                uuid = UUID.randomUUID();
            }
            s.setString(i, uuid.toString());
            i++;
            for (Property property : layout.getProperties()) {
                Object value = property.get(object);
                i = setValue(connection, s, i, value, property.getTypeHandler());
            }
            s.execute();
            PreparedStatement layoutsInsertion = connection.prepareStatement("INSERT INTO eventsourcing.layouts " +
                                                                                     "VALUES (?::UUID, " +
                                                                                     "?)");
            layoutsInsertion.setString(1, uuid.toString());
            layoutsInsertion.setBytes(2, layout.getHash());
            layoutsInsertion.execute();
            s.close();
            return uuid;
        }

        @SneakyThrows
        private int setValue(Connection connection, PreparedStatement s, int i, Object value, TypeHandler typeHandler) {
            if (typeHandler instanceof BigDecimalTypeHandler) {
                s.setBigDecimal(i, value == null ? BigDecimal.ZERO : (BigDecimal) value);
            } else
            if (typeHandler instanceof BooleanTypeHandler) {
                s.setBoolean(i, value == null ? false: (Boolean) value);
            } else
            if (typeHandler instanceof ByteArrayTypeHandler) {
                if (((ByteArrayTypeHandler) typeHandler).isPrimitive()) {
                    s.setBytes(i, value == null ? new byte[]{} : (byte[]) value);
                } else {
                    s.setBytes(i, value == null ?  new byte[]{} :
                            (byte[]) ((ByteArrayTypeHandler) typeHandler).toPrimitive(value));
                }
            } else
            if (typeHandler instanceof ByteTypeHandler) {
                s.setByte(i, value == null ? 0 : (Byte) value);
            } else
            if (typeHandler instanceof DateTypeHandler) {
                s.setTimestamp(i, value == null ? Timestamp.from(Instant.EPOCH) :
                        Timestamp.from(((Date)value).toInstant()));
            } else
            if (typeHandler instanceof DoubleTypeHandler) {
                s.setDouble(i, value == null ? 0 : (Double) value);
            } else
            if (typeHandler instanceof EnumTypeHandler) {
                s.setInt(i, value == null ? 0 : ((Enum)value).ordinal());
            } else
            if (typeHandler instanceof FloatTypeHandler) {
                s.setFloat(i, value == null ? 0 : (Float) value);
            } else
            if (typeHandler instanceof IntegerTypeHandler) {
                s.setInt(i, value == null ? 0 : (Integer) value);
            } else
            if (typeHandler instanceof ListTypeHandler) {
                int j=i;
                TypeHandler handler = ((ListTypeHandler) typeHandler).getWrappedHandler();
                for (Object item : (value == null ? Arrays.asList() : (List)value)) {
                    j = setValue(connection, s, j, item, handler);
                }
                return j;
            } else
            if (typeHandler instanceof LongTypeHandler) {
                s.setLong(i, value == null ? 0 : (Long) value);
            } else
            if (typeHandler instanceof ObjectTypeHandler) {
                Layout<?> layout = ((ObjectTypeHandler) typeHandler).getLayout();
                Object value_ = value == null ? layout.instantiate() : value;
                List<? extends Property<?>> properties = layout.getProperties();
                int j=i;
                for (Property p : properties) {
                    j = setValue(connection, s, j, p.get(value_), p.getTypeHandler());
                }
                return j;
            } else
            if (typeHandler instanceof OptionalTypeHandler) {
                TypeHandler handler = ((OptionalTypeHandler) typeHandler).getWrappedHandler();
                if (value != null && ((Optional)value).isPresent()) {
                    setValue(connection, s, i, ((Optional) value).get(),
                             handler);
                } else {
                    s.setNull(i, getMappedSqlType(handler));
                }
            } else
            if (typeHandler instanceof ShortTypeHandler) {
                s.setShort(i, value == null ? 0 : (Short) value);
            } else
            if (typeHandler instanceof StringTypeHandler) {
                s.setString(i, value == null ? "" : (String) value);
            } else
            if (typeHandler instanceof UUIDTypeHandler) {
                s.setString(i, value == null ? new UUID(0,0).toString() : value.toString());
            } else {
                throw new RuntimeException("Unsupported type handler " + typeHandler.getClass());
            }
            return i+1;
        }
    }

    private Map<String, Layout> layoutsByClass = new HashMap<>();
    private Map<String, Layout> layoutsByHash = new HashMap<>();

    private class LayoutExtractor implements Consumer<Class<?>> {
        @SneakyThrows
        @Override public void accept(Class<?> aClass) {
            Layout<?> layout = Layout.forClass(aClass);
            layoutsByClass.put(aClass.getName(), layout);
            byte[] fingerprint = layout.getHash();
            String encoded = BaseEncoding.base16().encode(fingerprint);
            layoutsByHash.put(encoded, layout);
            Connection connection = dataSource.getConnection();

            String columns = defineColumns(connection, layout);

            String createTable = "CREATE TABLE IF NOT EXISTS layout_" + encoded + " (" +
                    "uuid UUID PRIMARY KEY," +
                    columns +
                    ")";
            PreparedStatement s = connection.prepareStatement(createTable);
            s.execute();
            s.close();
            s = connection.prepareStatement("COMMENT ON TABLE layout_" + encoded + " IS '" + layout.getName() + "'");
            s.execute();
            s.close();
            connection.close();

            InsertFunction insertFunction = new InsertFunction(layout);
            insertFunctions.put(encoded, insertFunction);

            ReaderFunction readerFunction = new ReaderFunction(layout);
            readerFunctions.put(encoded, readerFunction);
        }

    }

    protected static String defineColumns(Connection connection, Layout<?> layout) {
        return Joiner.on(",\n").join(layout.getProperties().stream()
                                           .map(p -> "\"" + p.getName() + "\" " +
                                                          getMappedType(connection, p.getTypeHandler()))
                                           .collect(Collectors.toList()));
    }


    @SneakyThrows
    static String getMappedType(Connection connection, TypeHandler typeHandler) {
        if (typeHandler instanceof BigDecimalTypeHandler) {
            return "NUMERIC";
        }
        if (typeHandler instanceof BooleanTypeHandler) {
            return "BOOLEAN";
        }
        if (typeHandler instanceof ByteArrayTypeHandler) {
            return "BYTEA";
        }
        if (typeHandler instanceof ByteTypeHandler) {
            return "SMALLINT";
        }
        if (typeHandler instanceof DateTypeHandler) {
            return "TIMESTAMP";
        }
        if (typeHandler instanceof DoubleTypeHandler) {
            return "DOUBLE PRECISION";
        }
        if (typeHandler instanceof EnumTypeHandler) {
            return "INTEGER";
        }
        if (typeHandler instanceof FloatTypeHandler) {
            return "REAL";
        }
        if (typeHandler instanceof IntegerTypeHandler) {
            return "INTEGER";
        }
        if (typeHandler instanceof ListTypeHandler) {
            return getMappedType(connection, ((ListTypeHandler) typeHandler).getWrappedHandler()) +
                    "[]";
        }
        if (typeHandler instanceof LongTypeHandler) {
            return "BIGINT";
        }
        if (typeHandler instanceof ObjectTypeHandler) {
            Layout<?> layout = ((ObjectTypeHandler) typeHandler).getLayout();
            byte[] fingerprint = layout.getHash();
            String encoded = BaseEncoding.base16().encode(fingerprint);
            String typname = "layout_" + encoded;

            PreparedStatement check = connection
                    .prepareStatement("SELECT * FROM pg_catalog.pg_type WHERE lower(typname) = lower(?)");

            check.setString(1, typname);

            boolean shouldCreateType;
            try (ResultSet resultSet = check.executeQuery()) {
                shouldCreateType = !resultSet.next();
            }
            check.close();

            if (shouldCreateType) {
                String columns = defineColumns(connection, layout);
                String createType = "CREATE TYPE " + typname + " AS (" +
                        columns +
                        ")";
                PreparedStatement s = connection.prepareStatement(createType);
                s.execute();
                s.close();
                s = connection.prepareStatement("COMMENT ON TYPE " + typname + " IS '" + layout.getName() + "'");
                s.execute();
                s.close();
            }

            return typname;
        }
        if (typeHandler instanceof OptionalTypeHandler) {
            return getMappedType(connection, ((OptionalTypeHandler) typeHandler).getWrappedHandler());
        }
        if (typeHandler instanceof ShortTypeHandler) {
            return "SMALLINT";
        }
        if (typeHandler instanceof StringTypeHandler) {
            return "TEXT";
        }
        if (typeHandler instanceof UUIDTypeHandler) {
            return "UUID";
        }
        throw new RuntimeException("Unsupported type handler " + typeHandler.getClass());
    }

    static int getMappedSqlType(TypeHandler typeHandler) {
        if (typeHandler instanceof BigDecimalTypeHandler) {
            return Types.DECIMAL;
        }
        if (typeHandler instanceof BooleanTypeHandler) {
            return Types.BOOLEAN;
        }
        if (typeHandler instanceof ByteArrayTypeHandler) {
            return Types.BINARY;
        }
        if (typeHandler instanceof ByteTypeHandler) {
            return Types.SMALLINT;
        }
        if (typeHandler instanceof DateTypeHandler) {
            return Types.TIMESTAMP;
        }
        if (typeHandler instanceof DoubleTypeHandler) {
            return Types.DOUBLE;
        }
        if (typeHandler instanceof EnumTypeHandler) {
            return Types.INTEGER;
        }
        if (typeHandler instanceof FloatTypeHandler) {
            return Types.FLOAT;
        }
        if (typeHandler instanceof IntegerTypeHandler) {
            return Types.INTEGER;
        }
        if (typeHandler instanceof ListTypeHandler) {
            return Types.ARRAY;
        }
        if (typeHandler instanceof LongTypeHandler) {
            return Types.BIGINT;
        }
        if (typeHandler instanceof ObjectTypeHandler) {
            return Types.VARCHAR;
        }
        if (typeHandler instanceof OptionalTypeHandler) {
            return getMappedSqlType(((OptionalTypeHandler) typeHandler).getWrappedHandler());
        }
        if (typeHandler instanceof ShortTypeHandler) {
            return Types.SMALLINT;
        }
        if (typeHandler instanceof StringTypeHandler) {
            return Types.VARCHAR;
        }
        if (typeHandler instanceof UUIDTypeHandler) {
            return Types.VARCHAR;
        }
        throw new RuntimeException("Unsupported type handler " + typeHandler.getClass());
    }

    private class EventConsumer implements Consumer<Event> {
        private final HybridTimestamp ts;
        private final Journal.Listener listener;
        private final Connection connection;

        public EventConsumer(Connection connection, Command<?, ?> command, Journal.Listener listener) {
            this.connection = connection;
            this.listener = listener;
            this.ts = command.timestamp().clone();
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
            String encoded = BaseEncoding.base16().encode(layout.getHash());
            InsertFunction insert = insertFunctions.get(encoded);
            insert.apply(event, connection);

            listener.onEvent(event);
        }
    }
}
