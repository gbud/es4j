/**
 * Copyright (c) 2016, All Contributors (see CONTRIBUTORS file)
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.eventsourcing.cep.protocols;

import com.eventsourcing.EntityHandle;
import com.eventsourcing.Protocol;
import com.eventsourcing.cep.events.NameChanged;
import com.googlecode.cqengine.query.option.EngineThresholds;
import com.googlecode.cqengine.resultset.ResultSet;
import org.unprotocols.coss.Draft;
import org.unprotocols.coss.RFC;

import static com.googlecode.cqengine.query.QueryFactory.*;

@Draft @RFC(url = "http://rfc.eventsourcing.com/spec:3/CEP")
public interface NameProtocol extends Protocol {
    default String name() {
        try (ResultSet<EntityHandle<NameChanged>> resultSet =
                     getRepository().query(NameChanged.class, equal(NameChanged.REFERENCE_ID, id()),
                                      queryOptions(orderBy(descending(NameChanged.TIMESTAMP)),
                                                   applyThresholds(threshold(EngineThresholds.INDEX_ORDERING_SELECTIVITY, 0.5))))) {
            if (resultSet.isEmpty()) {
                return null;
            }
            return resultSet.iterator().next().get().name();
        }
    }
}
