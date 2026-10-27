package io.github.tortaje.fitnesse.health;

import fitnesse.FitNesseContext;
import fitnesse.Responder;
import fitnesse.http.Request;
import fitnesse.http.Response;

public class LivenessResponder implements Responder {

    @Override
    public Response makeResponse(FitNesseContext context, Request request) {
        LivenessProbe.Result result = HealthService.forContext(context).liveness();
        return JsonResponses.of(result.httpStatus(), result.toJsonMap());
    }
}
