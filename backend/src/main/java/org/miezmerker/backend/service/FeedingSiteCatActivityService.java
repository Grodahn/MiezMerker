package org.miezmerker.backend.service;

import java.util.List;
import java.util.UUID;
import org.miezmerker.backend.repo.FeedingSiteCatActivityRepository;
import org.miezmerker.backend.web.FeedingSiteCatActivityView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FeedingSiteCatActivityService {
    private final FeedingSiteService sites;
    private final FeedingSiteCatActivityRepository activity;

    public FeedingSiteCatActivityService(FeedingSiteService sites,
            FeedingSiteCatActivityRepository activity) {
        this.sites = sites;
        this.activity = activity;
    }

    @Transactional(readOnly = true)
    public List<FeedingSiteCatActivityView> list(UUID user, UUID org, UUID site,
            int limit, int offset) {
        sites.get(user, org, site); // ACTIVE membership and tenant-scoped 404 before querying activity.
        if (limit < 1 || limit > 1000 || offset < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit must be 1..1000 and offset must be >= 0");
        }
        return activity.list(org, site, limit, offset);
    }
}
