/**
 * Copyright (c) 2016, All Contributors (see CONTRIBUTORS file)
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.eventsourcing.cep.protocols;

import com.eventsourcing.*;
import com.eventsourcing.cep.events.DescriptionChanged;
import com.eventsourcing.hlc.HybridTimestamp;
import lombok.Builder;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.experimental.Accessors;
import org.testng.annotations.Test;

import java.util.UUID;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class DescriptionProtocolTest extends RepositoryTest {

    public DescriptionProtocolTest() {
        super(DescriptionChanged.class.getPackage(), DescriptionProtocolTest.class.getPackage());
    }

    @Accessors(fluent = true)
    public static class ChangeDescription extends StandardCommand<Void, String> {

        @Getter
        private final UUID id;
        @Getter
        private final String description;

        @Builder
        public ChangeDescription(HybridTimestamp timestamp, UUID id, String description) {
            super(timestamp);
            this.id = id;
            this.description = description;
        }

        @Override
        public EventStream<Void> events(Repository repository) throws Exception {
            return EventStream.of(DescriptionChanged.builder()
                    .reference(id)
                    .description(description)
                    .timestamp(timestamp()).build());
        }

        @Override
        public String result() {
            return description;
        }
    }

    @Accessors(fluent = true)
    public static class TestModel implements Model, DescriptionProtocol {

        @Getter @Accessors(fluent = false)
        private final Repository repository;

        @Getter
        private final UUID id;

        public TestModel(Repository repository, UUID id) {
            this.repository = repository;
            this.id = id;
        }

    }

    @Test
    @SneakyThrows
    public void changingDescription() {
        HybridTimestamp timestamp = new HybridTimestamp(timeProvider);
        timestamp.update();

        TestModel model = new TestModel(repository, UUID.randomUUID());

        ChangeDescription changeDescription = ChangeDescription.builder()
                                                               .id(model.id())
                                                               .description("Description #1").build();
        repository.publish(changeDescription).get();
        assertEquals(model.description(), "Description #1");

        ChangeDescription changeBefore = ChangeDescription.builder()
                                                          .id(model.id()).description("Description #0")
                                                          .timestamp(timestamp).build();
        assertTrue(changeBefore.timestamp().compareTo(changeDescription.timestamp()) < 0);
        repository.publish(changeBefore).get();
        assertEquals(model.description(), "Description #1"); // earlier change shouldn't affect the description


        changeDescription = ChangeDescription.builder()
                                             .id(model.id())
                                             .description("Description #2").build();
        repository.publish(changeDescription).get();
        assertEquals(model.description(), "Description #2");
    }
}