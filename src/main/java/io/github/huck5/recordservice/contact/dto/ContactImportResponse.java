package io.github.huck5.recordservice.contact.dto;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import io.github.huck5.recordservice.CrmSource;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class ContactImportResponse {
    private final CrmSource source;
    private final int fetched;
    private final int imported;
    private final int skipped;
    private final int invalidExternalId;
    private final int invalidContact;
    private final int failed;

    private ContactImportResponse(Builder builder) {
        this.source = builder.source;
        this.fetched = builder.fetched;
        this.imported = builder.imported;
        this.skipped = builder.skipped;
        this.invalidExternalId = builder.invalidExternalId;
        this.invalidContact = builder.invalidContact;
        this.failed = builder.failed;
    }

    public CrmSource getSource() {
        return source;
    }

    public int getFetched() {
        return fetched;
    }

    public int getImported() {
        return imported;
    }

    public int getSkipped() {
        return skipped;
    }

    public int getInvalidExternalId() {
        return invalidExternalId;
    }

    public int getInvalidContact() {
        return invalidContact;
    }

    public int getFailed() {
        return failed;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private CrmSource source;
        private int fetched;
        private int imported;
        private int skipped;
        private int invalidExternalId;
        private int invalidContact;
        private int failed;

        public Builder source(CrmSource source) {
            this.source = source;
            return this;
        }

        public Builder fetched(int fetched) {
            this.fetched = fetched;
            return this;
        }

        public Builder imported(int imported) {
            this.imported = imported;
            return this;
        }

        public Builder skipped(int skipped) {
            this.skipped = skipped;
            return this;
        }

        public Builder invalidExternalId(int invalidExternalId) {
            this.invalidExternalId = invalidExternalId;
            return this;
        }

        public Builder invalidContact(int invalidContact) {
            this.invalidContact = invalidContact;
            return this;
        }

        public Builder failed(int failed) {
            this.failed = failed;
            return this;
        }

        public ContactImportResponse build() {
            return new ContactImportResponse(this);
        }
    }
}
