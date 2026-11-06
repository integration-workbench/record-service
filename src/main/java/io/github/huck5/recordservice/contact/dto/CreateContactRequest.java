package io.github.huck5.recordservice.contact.dto;

import jakarta.validation.constraints.Email;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import jakarta.validation.constraints.Size;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class CreateContactRequest {
    @Size(max = 50)
    private String firstName;
    @Size(max = 80)
    private String lastName;
    @Email
    private String email;

    public CreateContactRequest(String firstName, String lastName, String email) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.email =email;
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
}
