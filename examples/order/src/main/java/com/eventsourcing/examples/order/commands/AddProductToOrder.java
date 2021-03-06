/**
 * Copyright (c) 2016, All Contributors (see CONTRIBUTORS file)
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.eventsourcing.examples.order.commands;

import com.eventsourcing.EventStream;
import com.eventsourcing.Repository;
import com.eventsourcing.StandardCommand;
import com.eventsourcing.examples.order.Order;
import com.eventsourcing.examples.order.events.ProductAddedToOrder;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

import java.util.UUID;

@Accessors(fluent = true)
public class AddProductToOrder extends StandardCommand<ProductAddedToOrder, Order.Item> {

    @Getter @NonNull
    private final UUID orderId;

    @Getter @NonNull
    private final UUID productId;

    @Getter @NonNull
    private final Integer quantity;

    @Builder
    public AddProductToOrder(UUID orderId, UUID productId, Integer quantity) {
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
    }

    @Override
    public EventStream<ProductAddedToOrder> events(Repository repository) throws Exception {
        ProductAddedToOrder addedToOrder = ProductAddedToOrder.builder()
                .orderId(orderId).productId(productId).quantity(quantity).build();
        return EventStream.ofWithState(addedToOrder, addedToOrder);
    }

    @Override
    public Order.Item result(ProductAddedToOrder productAddedToOrder, Repository repository) {
        return Order.lookup(repository, orderId).get().items().stream().
                filter(item -> item.id().equals(productAddedToOrder.uuid())).findFirst().get();
    }
}
