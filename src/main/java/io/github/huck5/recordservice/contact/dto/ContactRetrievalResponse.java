package io.github.huck5.recordservice.contact.dto;

import java.util.List;

public record ContactRetrievalResponse(
        List<CrmContactDto> contacts,
        Long totalAvailable,
        boolean hasMore,
        String continuationToken
) {}