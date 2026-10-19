package com.thinklab.application.mapper;

import com.thinklab.application.dto.response.ResourceResponse;
import com.thinklab.domain.model.CloudResource;
import com.thinklab.domain.model.CloudResource.Presence;

public final class InventoryMapper {

    private InventoryMapper() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /** The resource as it stands, with how it was judged. */
    public static ResourceResponse toResponse(CloudResource resource, Presence presence) {
        return new ResourceResponse(resource.getId(), resource.getOrganisationId(), resource.getAccountId(), resource.getProvider().name(), resource.getExternalId(),
                resource.getName(), resource.getKind().name(), resource.getRegion(), resource.getState().name(), resource.getTags(), presence.name(),
                resource.getFirstSeenAt(), resource.getLastSeenAt(), resource.getUpdatedAt());
    }
}
