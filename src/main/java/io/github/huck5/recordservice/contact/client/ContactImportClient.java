package io.github.huck5.recordservice.contact.client;

import io.github.huck5.recordservice.CrmSource;
import io.github.huck5.recordservice.contact.dto.ContactRetrievalResponse;
import io.github.huck5.recordservice.contact.exception.ImportRequestFailedException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
public class ContactImportClient {
    private static final String CRM_SERVICE_URI = "http://localhost:8080/%s/contacts";

    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;

    public ContactImportClient(HttpClient httpClient, JsonMapper jsonMapper) {
        this.httpClient = httpClient;
        this.jsonMapper = jsonMapper;
    }

    public ContactRetrievalResponse fetchPage(CrmSource crmSource, String continuationToken) {
        String url = CRM_SERVICE_URI.formatted(crmSource.name().toLowerCase(Locale.ROOT));

        if (StringUtils.hasText(continuationToken)) {
            url += "?continuationToken=" + URLEncoder.encode(continuationToken, StandardCharsets.UTF_8);
        }

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<String> response;

        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new ImportRequestFailedException("Import contacts failed. Status code %s" .formatted(response.statusCode()));
            }
        } catch (IOException e) {
            throw new ImportRequestFailedException("Could not retrieve contacts from " + crmSource, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImportRequestFailedException("Contact retrieval interrupted for " + crmSource, e);
        }

        // Successfully retrieved contacts for specified CRM
        return jsonMapper.readValue(response.body(), new TypeReference<>() {});
    }
}
