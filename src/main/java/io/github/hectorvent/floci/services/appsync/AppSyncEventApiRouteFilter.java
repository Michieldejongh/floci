package io.github.hectorvent.floci.services.appsync;

import io.github.hectorvent.floci.core.common.ResolvedServiceCatalog;
import io.github.hectorvent.floci.core.common.ServiceDescriptor;
import io.github.hectorvent.floci.core.common.SigV4CredentialScope;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

import java.util.regex.Pattern;

/**
 * Rewrites AppSync Event API requests to an internal prefix before JAX-RS route matching.
 * AppSync ({@code CreateApi}, {@code GetApi}, {@code UpdateApi}, {@code DeleteApi},
 * {@code ListApis}) and API Gateway v2 expose the same {@code /v2/apis} and
 * {@code /v2/apis/{apiId}} paths, so only the request's SigV4 signing service tells them apart.
 * Deeper AppSync paths such as {@code /v2/apis/{apiId}/channelNamespaces} do not collide and are
 * left alone.
 */
@Provider
@PreMatching
public class AppSyncEventApiRouteFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/_appsync";
    private static final String APPSYNC = "appsync";
    private static final Pattern EVENT_API_PATH = Pattern.compile("^/v2/apis(/[^/]+)?/?$");

    private final ResolvedServiceCatalog catalog;

    @Inject
    public AppSyncEventApiRouteFilter(ResolvedServiceCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        if (!EVENT_API_PATH.matcher(normalizedPath).matches()) {
            return;
        }
        boolean targetsAppSync = SigV4CredentialScope.serviceName(ctx.getHeaderString("Authorization"))
                .flatMap(catalog::byCredentialScope)
                .map(ServiceDescriptor::externalKey)
                .filter(APPSYNC::equals)
                .isPresent();
        if (!targetsAppSync) {
            return;
        }
        ctx.setRequestUri(ctx.getUriInfo().getRequestUriBuilder()
                .replacePath(INTERNAL_PREFIX + normalizedPath)
                .build());
    }
}
