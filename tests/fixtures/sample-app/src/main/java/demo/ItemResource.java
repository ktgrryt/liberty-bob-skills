package demo;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

public class ItemResource {

    private final long orderId;

    public ItemResource(long orderId) {
        this.orderId = orderId;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String list() {
        return "[]";
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public void add(String item) {
    }
}
