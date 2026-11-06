package io.github.huck5.recordservice.contact.dto;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

@SuppressWarnings({"unused", "FieldCanBeLocal"})
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class ContactDto {
    private final Long id;
    private final String firstName;
    private final String lastName;
    private final String email;
    private final Long version;

    private ContactDto(Builder builder) {
        this.id = builder.id;
        this.firstName = builder.firstName;
        this.lastName = builder.lastName;
        this.email = builder.email;
        this.version = builder.version;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Long id;
        private String firstName;
        private String lastName;
        private String email;
        private Long version;

        public Builder id(Long id) {
            this.id = id;
            return this;
        }

        public Builder firstName(String firstName) {
            this.firstName = firstName;
            return this;
        }
        public Builder lastName(String lastName) {
            this.lastName = lastName;
            return this;
        }
        public Builder email(String email) {
            this.email = email;
            return this;
        }
        public Builder version(Long version) {
            this.version = version;
            return this;
        }

        public ContactDto build() {
            return new ContactDto(this);
        }
    }
}
