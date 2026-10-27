package io.github.tortaje.fitnesse.health;

import fitnesse.FitNesseContext;
import fitnesse.Responder;
import fitnesse.http.Request;
import fitnesse.http.Response;

public class ReadinessResponder implements Responder {

    @Override
    public Response makeResponse(FitNesseContext context, Request request) {
        ReadinessReport report = HealthService.forContext(context).readiness();
        return JsonResponses.of(report.httpStatus(), report.toJsonMap());
    }
}
