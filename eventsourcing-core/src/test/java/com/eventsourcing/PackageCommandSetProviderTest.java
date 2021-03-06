/**
 * Copyright (c) 2016, All Contributors (see CONTRIBUTORS file)
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.eventsourcing;

import boguspackage.BogusCommand;
import com.eventsourcing.repository.PackageCommandSetProvider;
import org.testng.annotations.Test;

public class PackageCommandSetProviderTest {

    @Test
    public void test() {
        new PackageCommandSetProvider(new Package[]{BogusCommand.class.getPackage()}).getCommands()
                                                                                     .contains(BogusCommand.class);
    }
}