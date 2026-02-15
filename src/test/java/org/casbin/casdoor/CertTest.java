// Copyright 2023 The Casdoor Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.casbin.casdoor;

import org.casbin.casdoor.entity.Cert;
import org.casbin.casdoor.service.CertService;
import org.casbin.casdoor.support.TestDefaultConfig;

import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CertTest extends BaseCasdoorTest {

    private CertService certService;

    @BeforeEach
    void setUp() {
        certService = new CertService(config);
    }

    @Test
    public void testCert() {
        String name = TestDefaultConfig.getRandomName("cert");

        // Add a new object
        Cert cert = new Cert(
                "admin",
                name,
                LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME),
                name,
                "JWT",
                "x509",
                "RS256",
                4096,
                20
        );
        assertDoesNotThrow(() -> certService.addCert(cert));

        // Get all objects, check if our added object is inside the list
        List<Cert> certs;
        try {
            certs = certService.getCerts();
        } catch (Exception e) {
            fail("Failed to get objects: " + e.getMessage());
            return;
        }

        boolean found = certs.stream().anyMatch(item -> item.name.equals(name));
        assertTrue(found, "Added object not found in list");

        // Get the object
        Cert retrievedCert;
        try {
            retrievedCert = certService.getCert(name);
        } catch (Exception e) {
            fail("Failed to get object: " + e.getMessage());
            return;
        }
        assertEquals(name, retrievedCert.name, "Retrieved object does not match added object");

        // Update the object
        String updatedDisplayName = "Updated Casdoor Website";
        retrievedCert.displayName = updatedDisplayName;
        assertDoesNotThrow(() -> certService.updateCert(retrievedCert));

        // Validate the update
        Cert updatedCert;
        try {
            updatedCert = certService.getCert(name);
        } catch (Exception e) {
            fail("Failed to get updated object: " + e.getMessage());
            return;
        }
        assertEquals(updatedDisplayName, updatedCert.displayName, "Failed to update object, displayName mismatch");

        // Delete the object
        assertDoesNotThrow(() -> certService.deleteCert(cert));

        // Validate the deletion
        Cert deletedCert;
        try {
            deletedCert = certService.getCert(name);
        } catch (Exception e) {
            fail("Failed to delete object: " + e.getMessage());
            return;
        }
        assertNull(deletedCert, "Failed to delete object, it's still retrievable");
    }


}
