package io.github.huck5.recordservice.contact.client;

import io.github.huck5.recordservice.contact.dto.ContactRetrievalResponse;
import io.github.huck5.recordservice.contact.exception.ImportRequestFailedException;
import tools.jackson.core.type.TypeReference;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class ContactImportClient {

    public ContactRetrievalResponse importContacts() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(CRM_SERVICE_URI.formatted(crmSource.toString().toLowerCase())))
                .GET()
                .build();
        HttpResponse<String> response;

        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new ImportRequestFailedException(
                        "Import contacts failed. Status code %s".formatted(response.statusCode()));
            }
        } catch (IOException e) {
            throw new RuntimeException("Import contacts request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Import contacts request interrupted.", e);
        }

        // Successfully retrieved contacts for specified CRM
        ContactRetrievalResponse retrievalResponse = jsonMapper.readValue(response.body(), new TypeReference<>() {
        });

        return retrievalResponse;
    }
}
