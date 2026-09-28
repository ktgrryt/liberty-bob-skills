package demo;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "external")
@Path("/v1/status")
public interface ExternalApi {

    @GET
    String status();
}
