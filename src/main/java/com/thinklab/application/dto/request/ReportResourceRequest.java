package com.thinklab.application.dto.request;

import com.thinklab.domain.model.CloudResource.Kind;
import com.thinklab.domain.model.CloudResource.Provider;
import com.thinklab.domain.model.CloudResource.State;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

/**
 * DTO for {@code initiate}: what the discovery tool says one resource of a cloud account is. The provider's own id says which resource it is. The
 * tags are optional and only four keys are kept (environment, owner, cost-center, application).
 */
@Serdeable
public record ReportResourceRequest(
        @NotNull(message = "The account is required") UUID accountId,
        @NotNull(message = "The provider is required") Provider provider,
        @NotBlank(message = "The provider's id for the resource is required")
        @Pattern(regexp = "[A-Za-z0-9/][A-Za-z0-9._:/-]{0,299}", message = "The provider's id for the resource must be up to 300 letters, digits, dots, dashes, underscores, colons or slashes")
        String externalId,
        @NotBlank(message = "The name is required")
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}", message = "The name must be up to 128 letters, digits, dots, dashes, underscores, colons or slashes")
        String name,
        @NotNull(message = "The kind is required") Kind kind,
        @NotBlank(message = "The region is required")
        @Pattern(regexp = "[a-z0-9-]{2,40}", message = "The region must be 2 to 40 lowercase letters, digits or dashes")
        String region,
        @NotNull(message = "The state is required") State state,
        @Nullable @Size(max = 4, message = "At most four tags are kept") Map<String, String> tags
) {}
