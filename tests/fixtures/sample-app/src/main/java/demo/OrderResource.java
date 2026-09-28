package demo;

import jakarta.enterprise.context.RequestScoped;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;

@Path("/orders")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
public class OrderResource {

    private final Jsonb jsonb = JsonbBuilder.create();

    @GET
    public String list() {
        return jsonb.toJson(List.of(Map.of("id", 1)));
    }

    @GET
    @Path("{id: \\d+}")
    public String get(@PathParam("id") long id) {
        return jsonb.toJson(Map.of("id", id));
    }

    @Path("{id}/items")
    public ItemResource items(@PathParam("id") long id) {
        return new ItemResource(id);
    }
}
