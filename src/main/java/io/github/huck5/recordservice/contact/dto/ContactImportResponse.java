package io.github.huck5.recordservice.contact.dto;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import io.github.huck5.recordservice.CrmSource;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class ContactImportResponse {
    private final CrmSource source;
    private final long fetched;
    private final long imported;
    private final long skipped;
    private final long invalidExternalId;
    private final long invalidContact;
    private final long failed;

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

    public long getFetched() {
        return fetched;
    }

    public long getImported() {
        return imported;
    }

    public long getSkipped() {
        return skipped;
    }

    public long getInvalidExternalId() {
        return invalidExternalId;
    }

    public long getInvalidContact() {
        return invalidContact;
    }

    public long getFailed() {
        return failed;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private CrmSource source;
        private long fetched;
        private long imported;
        private long skipped;
        private long invalidExternalId;
        private long invalidContact;
        private long failed;

        public Builder source(CrmSource source) {
            this.source = source;
            return this;
        }

        public Builder fetched(long fetched) {
            this.fetched = fetched;
            return this;
        }

        public Builder imported(long imported) {
            this.imported = imported;
            return this;
        }

        public Builder skipped(long skipped) {
            this.skipped = skipped;
            return this;
        }

        public Builder invalidExternalId(long invalidExternalId) {
            this.invalidExternalId = invalidExternalId;
            return this;
        }

        public Builder invalidContact(long invalidContact) {
            this.invalidContact = invalidContact;
            return this;
        }

        public Builder failed(long failed) {
            this.failed = failed;
            return this;
        }

        public ContactImportResponse build() {
            return new ContactImportResponse(this);
        }
    }
}
