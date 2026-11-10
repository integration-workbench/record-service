package io.github.huck5.recordservice.contact.dto;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import io.github.huck5.recordservice.CrmSource;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

import java.util.Objects;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@JsonDeserialize(builder = CrmContactDto.Builder.class)
public class CrmContactDto {
    private final CrmSource source;
    private final String externalId;
    private final String firstName;
    private final String lastName;
    private final String email;

    private CrmContactDto(Builder builder) {
        this.source = builder.source;
        this.externalId = builder.externalId;
        this.firstName = builder.firstName;
        this.lastName = builder.lastName;
        this.email = builder.email;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getEmail() {
        return email;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        CrmContactDto that = (CrmContactDto) o;
        return source == that.source && Objects.equals(externalId, that.externalId) && Objects.equals(
                firstName, that.firstName) && Objects.equals(lastName, that.lastName) && Objects.equals(
                email, that.email);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, externalId, firstName, lastName, email);
    }

    @Override
    public String toString() {
        return "ContactDTO{" +
                "source=" + source +
                ", externalId='" + externalId + '\'' +
                ", firstName='" + firstName + '\'' +
                ", lastName='" + lastName + '\'' +
                ", email='" + email + '\'' +
                '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static class Builder {
        private CrmSource source;
        private String externalId;
        private String firstName;
        private String lastName;
        private String email;

        public Builder source(CrmSource source) {
            this.source = source;
            return this;
        }

        public Builder externalId(String externalId) {
            this.externalId = externalId;
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

        public CrmContactDto build() {
            return new CrmContactDto(this);
        }
    }
}
