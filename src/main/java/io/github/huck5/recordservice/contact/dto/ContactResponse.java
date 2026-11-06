package io.github.huck5.recordservice.contact.dto;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import org.springframework.data.domain.Page;

import java.util.List;

@SuppressWarnings({"unused", "FieldCanBeLocal"})
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class ContactResponse {
    private final List<ContactDto> contacts;
    private final long length;
    private final int pageIndex;
    private final int pageSize;

    private ContactResponse(List<ContactDto> contacts, long length, int pageIndex, int pageSize) {
        this.contacts = contacts;
        this.length = length;
        this.pageIndex = pageIndex;
        this.pageSize = pageSize;
    }

    public static ContactResponse from(Page<ContactDto> page) {
        return new ContactResponse(page.getContent(), page.getTotalElements(), page.getNumber(), page.getSize());
    }
}
