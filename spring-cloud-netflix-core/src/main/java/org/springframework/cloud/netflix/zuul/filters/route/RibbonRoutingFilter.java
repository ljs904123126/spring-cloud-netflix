package org.springframework.cloud.netflix.zuul.filters.route;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.MultivaluedMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.netflix.ribbon.SpringClientFactory;

import com.netflix.client.ClientException;
import com.netflix.client.http.HttpRequest.Verb;
import com.netflix.client.http.HttpResponse;
import com.netflix.hystrix.exception.HystrixRuntimeException;
import com.netflix.niws.client.http.RestClient;
import com.netflix.zuul.context.RequestContext;
import com.netflix.zuul.exception.ZuulException;
import com.netflix.zuul.util.HTTPRequestUtils;

public class RibbonRoutingFilter extends BaseProxyFilter {

	private static final Logger LOG = LoggerFactory.getLogger(RibbonRoutingFilter.class);

	public static final String CONTENT_ENCODING = "Content-Encoding";

	private SpringClientFactory clientFactory;

	public RibbonRoutingFilter(SpringClientFactory clientFactory) {
		this.clientFactory = clientFactory;
	}

	@Override
	public String filterType() {
		return "route";
	}

	@Override
	public int filterOrder() {
		return 10;
	}

	public boolean shouldFilter() {
		RequestContext ctx = RequestContext.getCurrentContext();
		return (ctx.getRouteHost() == null && ctx.get("serviceId") != null && ctx
				.sendZuulResponse());
	}

	public Object run() {
		RequestContext context = RequestContext.getCurrentContext();
		HttpServletRequest request = context.getRequest();

		MultivaluedMap<String, String> headers = buildZuulRequestHeaders(request);
		MultivaluedMap<String, String> params = buildZuulRequestQueryParams(request);
		Verb verb = getVerb(request);
		InputStream requestEntity = getRequestBody(request);

		String serviceId = (String) context.get("serviceId");

		RestClient restClient = clientFactory.getClient(serviceId, RestClient.class);

		String uri = request.getRequestURI();
		if (context.get("requestURI") != null) {
			uri = (String) context.get("requestURI");
		}
		// remove double slashes
		uri = uri.replace("//", "/");

		try {
			HttpResponse response = forward(restClient, verb, uri, headers, params,
					requestEntity);
			setResponse(response);
			return response;
		}
		catch (Exception e) {
			context.set("error.status_code", HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			context.set("error.exception", e);
		}
		return null;
	}

	private HttpResponse forward(RestClient restClient, Verb verb, String uri,
			MultivaluedMap<String, String> headers,
			MultivaluedMap<String, String> params, InputStream requestEntity)
			throws Exception {

		Map<String, Object> info = debug(verb.verb(), uri, headers, params, requestEntity);

		RibbonCommand command = new RibbonCommand(restClient, verb, uri, headers, params,
				requestEntity);
		try {
			HttpResponse response = command.execute();
			appendDebug(info, response.getStatus(), response.getHeaders());
			return response;
		}
		catch (HystrixRuntimeException e) {
			info.put("status", "500");
			if (e.getFallbackException() != null
					&& e.getFallbackException().getCause() != null
					&& e.getFallbackException().getCause() instanceof ClientException) {
				ClientException ex = (ClientException) e.getFallbackException()
						.getCause();
				throw new ZuulException(ex, "Forwarding error", 500, ex.getErrorType()
						.toString());
			}
			throw new ZuulException(e, "Forwarding error", 500, e.getFailureType()
					.toString());
		}

	}

	private InputStream getRequestBody(HttpServletRequest request) {
		InputStream requestEntity = null;
		// ApacheHttpClient4Handler does not support body in delete requests
		if (request.getMethod().equals("DELETE")) {
			return null;
		}
		try {
			requestEntity = (InputStream) RequestContext.getCurrentContext().get(
					"requestEntity");
			if (requestEntity == null) {
				requestEntity = request.getInputStream();
			}
		}
		catch (IOException e) {
			LOG.error("Error during getRequestBody", e);
		}

		return requestEntity;
	}

	Verb getVerb(HttpServletRequest request) {
		String sMethod = request.getMethod();
		return getVerb(sMethod);
	}

	Verb getVerb(String sMethod) {
		if (sMethod == null)
			return Verb.GET;
		sMethod = sMethod.toLowerCase();
		if (sMethod.equals("post"))
			return Verb.POST;
		if (sMethod.equals("put"))
			return Verb.PUT;
		if (sMethod.equals("delete"))
			return Verb.DELETE;
		if (sMethod.equals("options"))
			return Verb.OPTIONS;
		if (sMethod.equals("head"))
			return Verb.HEAD;
		return Verb.GET;
	}

	void setResponse(HttpResponse resp) throws ClientException, IOException {
		RequestContext context = RequestContext.getCurrentContext();

		context.setResponseStatusCode(resp.getStatus());
		if (resp.hasEntity()) {
			context.setResponseDataStream(resp.getInputStream());
		}

		String contentEncoding = null;
		Collection<String> contentEncodingHeader = resp.getHeaders()
				.get(CONTENT_ENCODING);
		if (contentEncodingHeader != null && !contentEncodingHeader.isEmpty()) {
			contentEncoding = contentEncodingHeader.iterator().next();
		}

		if (contentEncoding != null
				&& HTTPRequestUtils.getInstance().isGzipped(contentEncoding)) {
			context.setResponseGZipped(true);
		}
		else {
			context.setResponseGZipped(false);
		}

		for (String key : resp.getHeaders().keySet()) {
			boolean isValidHeader = isIncludedHeader(key);
			Collection<java.lang.String> list = resp.getHeaders().get(key);
			for (String header : list) {
				context.addOriginResponseHeader(key, header);

				if (key.equalsIgnoreCase("content-length"))
					context.setOriginContentLength(header);

				if (isValidHeader) {
					context.addZuulResponseHeader(key, header);
				}
			}
		}

	}

	private boolean isIncludedHeader(String headerName) {
		switch (headerName.toLowerCase()) {
		case "connection":
		case "content-length":
		case "content-encoding":
		case "server":
		case "transfer-encoding":
			return false;
		default:
			return true;
		}
	}

}
