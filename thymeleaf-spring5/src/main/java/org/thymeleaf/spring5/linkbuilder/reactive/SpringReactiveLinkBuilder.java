/*
 * =============================================================================
 * 
 *   Copyright (c) 2011-2016, The THYMELEAF team (http://www.thymeleaf.org)
 * 
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 * 
 * =============================================================================
 */
package org.thymeleaf.spring5.linkbuilder.reactive;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.linkbuilder.AbstractLinkBuilder;
import org.thymeleaf.spring5.context.reactive.ISpringReactiveWebContext;
import org.thymeleaf.util.Validate;
import org.unbescape.uri.UriEscape;


/**
 *
 * @author Daniel Fern&aacute;ndez
 *
 * @since 3.0.3
 */
public class SpringReactiveLinkBuilder extends AbstractLinkBuilder {

    // Note we are making a difference between CONTEXT_RELATIVE and SERVER_RELATIVE, even if this kind of
    // applications won't have a "context path" and therefore server and context relative should behave the same...
    // but they are different formats ('/' vs '~/') and as such they need to be processed.
    protected enum LinkType { ABSOLUTE, CONTEXT_RELATIVE, SERVER_RELATIVE, BASE_RELATIVE }

    private static final char URL_TEMPLATE_DELIMITER_PREFIX = '{';
    private static final char URL_TEMPLATE_DELIMITER_SUFFIX = '}';
    private static final String URL_TEMPLATE_DELIMITER_SEGMENT_PREFIX = "{/";



    public SpringReactiveLinkBuilder() {
        super();
    }

    


    public final String buildLink(
            final IExpressionContext context, final String base, final Map<String, Object> parameters) {

        Validate.notNull(context, "Expression context cannot be null");

        if (base == null) {
            return null;
        }

        // We need to create a copy that is: 1. defensive, 2. mutable
        final Map<String,Object> linkParameters =
                (parameters == null || parameters.size() == 0? null : new LinkedHashMap<String, Object>(parameters));

        final LinkType linkType;
        if (isLinkBaseAbsolute(base)) {
            linkType = LinkType.ABSOLUTE;
        } else if (isLinkBaseContextRelative(base)) {
            linkType = LinkType.CONTEXT_RELATIVE;
        } else if (isLinkBaseServerRelative(base)) {
            linkType = LinkType.SERVER_RELATIVE;
        } else {
            linkType = LinkType.BASE_RELATIVE;
        }


        /*
         * Compute URL fragments (selectors after '#') so that they can be output at the end of
         * the URL, after parameters.
         */
        final int hashPosition = findCharInSequence(base, '#');


        /*
         * Compute whether we might have variable templates (e.g. Spring Path Variables) inside this link base
         * that we might need to resolve afterwards
         */
        final boolean mightHaveVariableTemplates = findCharInSequence(base, URL_TEMPLATE_DELIMITER_PREFIX) >= 0;


        /*
         * SHORTCUT - just before starting to work with StringBuilders, and in the case that we know: 1. That the URL is
         *            absolute, relative or context-relative; 2. That there are no parameters; and
         *            3. That there are no URL fragments -> then just return the base URL String without further
         *            processing (except HttpServletResponse-encoding if needed, of course...)
         */
        if (linkType != LinkType.SERVER_RELATIVE &&
                (linkParameters == null || linkParameters.size() == 0) && hashPosition < 0 && !mightHaveVariableTemplates) {
            return processLink(context, base);
        }


        /*
         * Build the StringBuilder that will be used as a base for all URL-related operations from now on: variable
         * templates, parameters, URL fragments...
         */
        StringBuilder linkBase = new StringBuilder(base);


        /*
         * Compute URL fragments (selectors after '#') so that they can be output at the end of
         * the URL, after parameters.
         */
        String urlFragment = "";
        // If hash position == 0 we will not consider it as marking an
        // URL fragment.
        if (hashPosition > 0) {
            // URL fragment String will include the # sign
            urlFragment = linkBase.substring(hashPosition);
            linkBase.delete(hashPosition, linkBase.length());
        }


        /*
         * Replace those variable templates that might appear referenced in the path itself, as for example, Spring
         * "Path Variables" (e.g. '/something/{variable}/othersomething')
         */
        if (mightHaveVariableTemplates) {
            linkBase = replaceTemplateParamsInBase(linkBase, linkParameters);
        }


        /*
         * Process parameters (those that have not already been processed as a result of replacing template
         * parameters in base).
         */
        if (linkParameters != null && linkParameters.size() > 0) {

            final boolean linkBaseHasQuestionMark = findCharInSequence(linkBase,'?') >= 0;

            // If there is no '?' in linkBase, we have to replace with first '&' with '?'
            if (linkBaseHasQuestionMark) {
                linkBase.append('&');
            } else {
                linkBase.append('?');
            }

            // Build the parameters query. The result will always start with '&'
            processAllRemainingParametersAsQueryParams(linkBase, linkParameters);

        }


        /*
         * Once parameters have been added (if there are parameters), we can add the URL fragment
         */
        if (urlFragment.length() > 0) {
            linkBase.append(urlFragment);
        }


        /*
         * If link base is server relative, we will delete now the leading '~' character so that it starts with '/'
         */
        if (linkType == LinkType.SERVER_RELATIVE) {
            linkBase.delete(0,1);
        }


        /*
         * Return the link, first performing the last processing on it. This will normally perform a standard
         * HttpServletResponse.encodeUrl(...) operation on it, but will give any subclasses the opportunity to
         * customize this behaviour (in case, for instance, they don't want to rely on the Java Servlet API).
         */
        return processLink(context, linkBase.toString());

    }

    


    private static int findCharInSequence(final CharSequence seq, final char character) {
        int n = seq.length();
        while (n-- != 0) {
            final char c = seq.charAt(n);
            if (c == character) {
                return n;
            }
        }
        return -1;
    }



    
    private static boolean isLinkBaseAbsolute(final CharSequence linkBase) {
        final int linkBaseLen = linkBase.length();
        if (linkBaseLen < 2) {
            return false;
        }
        final char c0 = linkBase.charAt(0);
        if (c0 == 'm' || c0 == 'M') {
            // Let's check for "mailto:"
            if (linkBase.length() >= 7 &&
                    Character.toLowerCase(linkBase.charAt(1)) == 'a' &&
                    Character.toLowerCase(linkBase.charAt(2)) == 'i' &&
                    Character.toLowerCase(linkBase.charAt(3)) == 'l' &&
                    Character.toLowerCase(linkBase.charAt(4)) == 't' &&
                    Character.toLowerCase(linkBase.charAt(5)) == 'o' &&
                    Character.toLowerCase(linkBase.charAt(6)) == ':') {
                return true;
            }
        } else if (c0 == '/' || c0 == '/') {
            return linkBase.charAt(1) == '/'; // It starts with '//' -> true, any other '/x' -> false
        }
        for (int i = 0; i < (linkBaseLen - 2); i++) {
            // Let's try to find the '://' sequence anywhere in the base --> true
            if (linkBase.charAt(i) == ':' && linkBase.charAt(i + 1) == '/' && linkBase.charAt(i + 2) == '/') {
                return true;
            }
        }
        return false;
    }


    private static boolean isLinkBaseContextRelative(final CharSequence linkBase) {
        // For this to be true, it should start with '/', but not with '//'
        if (linkBase.length() == 0 || linkBase.charAt(0) != '/') {
            return false;
        }
        return linkBase.length() == 1 || linkBase.charAt(1) != '/';
    }


    private static boolean isLinkBaseServerRelative(final CharSequence linkBase) {
        // For this to be true, it should start with '~/'
        return (linkBase.length() >= 2 && linkBase.charAt(0) == '~' && linkBase.charAt(1) == '/');
    }

    
    




    private static StringBuilder replaceTemplateParamsInBase(final StringBuilder linkBase, final Map<String, Object> parameters) {

        /*
         * If parameters is null, there's nothing to do
         */
        if (parameters == null) {
            return linkBase;
        }

        /*
         * Search {templateVar} in linkBase, and replace with value.
         * Parameters can be multivalued, in which case they will be comma-separated.
         * Parameter values will be URL-path-encoded. If there is a '?' char, only parameter values before this
         * char will be URL-path-encoded, whereas parameters after it will be URL-query-encoded.
         */

        final int questionMarkPosition = findCharInSequence(linkBase, '?');

        final Set<String> parameterNames = parameters.keySet();
        Set<String> alreadyProcessedParameters = null;

        for (final String parameterName : parameterNames) {

            // We default to escaping as a path, not a path segment
            boolean escapeAsPathSegment = false;

            // We use the text repository in order to avoid the unnecessary creation of too many instances of the same string
            String template = URL_TEMPLATE_DELIMITER_PREFIX + parameterName + URL_TEMPLATE_DELIMITER_SUFFIX;

            int templateIndex = linkBase.indexOf(template); // not great, because StringBuilder.indexOf ends up calling template.toCharArray(), but...

            if (templateIndex < 0) {
                template = URL_TEMPLATE_DELIMITER_SEGMENT_PREFIX + parameterName + URL_TEMPLATE_DELIMITER_SUFFIX;
                templateIndex = linkBase.indexOf(template);

                if (templateIndex < 0) {
                    // This parameter is not one of those used in path variables
                    continue;
                }

                // We need to escape this parameter value as a path segment rather than a path
                escapeAsPathSegment = true;
            }

            // Add the parameter name to the set of processed ones so that it is later removed from the parameters object
            if (alreadyProcessedParameters == null) {
                alreadyProcessedParameters = new HashSet<String>(parameterNames.size());
            }
            alreadyProcessedParameters.add(parameterName);

            // Compute the replacement (unescaped!)
            final Object parameterValue = parameters.get(parameterName);
            final String templateReplacement = formatParameterValueAsUnescapedVariableTemplate(parameterValue);
            final int templateReplacementLen = templateReplacement.length();

            // We will now use a the StringBuilder itself for replacing all appearances of the variable template in
            // the link base. Note we do this instead of using String#replace() because String#replace internally uses
            // pattern matching and is very slow :-(
            final int templateLen = template.length();
            int start = templateIndex;
            while (start > -1) {
                // Depending on whether the template appeared before or after the ?, we will apply different escaping
                final String escapedReplacement =
                        (questionMarkPosition == -1 || start < questionMarkPosition?
                                (escapeAsPathSegment ? UriEscape.escapeUriPathSegment(templateReplacement) : UriEscape.escapeUriPath(templateReplacement))
                                : UriEscape.escapeUriQueryParam(templateReplacement));
                linkBase.replace(start, start + templateLen, escapedReplacement);
                start = linkBase.indexOf(template, start + templateReplacementLen);
            }

        }

        if (alreadyProcessedParameters != null) {
            for (final String alreadyProcessedParameter : alreadyProcessedParameters) {
                parameters.remove(alreadyProcessedParameter);
            }
        }

        return linkBase;

    }





    /*
     * This method will return a String containing all the values for a specific parameter, separated with commas
     * and suitable therefore to be used as variable template (path variables) replacements
     */
    private static String formatParameterValueAsUnescapedVariableTemplate(final Object parameterValue) {
        // Get the value
        if (parameterValue == null) { // If null (= NO_VALUE), empty String
            return "";
        }
        // If it is not multivalued (e.g. non-List) simply escape and return
        if (!(parameterValue instanceof List<?>)) {
            return parameterValue.toString();
        }
        // It is multivalued, so iterate and escape each item (no need to escape the comma separating them, it's an allowed char)
        final List<?> values = (List<?>)parameterValue;
        final int valuesLen = values.size();
        final StringBuilder strBuilder = new StringBuilder(valuesLen * 16);
        for (int i = 0; i < valuesLen; i++) {
            final Object valueItem = values.get(i);
            if (strBuilder.length() > 0) {
                strBuilder.append(',');
            }
            strBuilder.append(valueItem == null? "" : valueItem.toString());
        }
        return strBuilder.toString();
    }



    private static void processAllRemainingParametersAsQueryParams(final StringBuilder strBuilder, final Map<String, Object> parameters) {

        final int parameterSize = parameters.size();

        if (parameterSize <= 0) {
            return;
        }

        final Set<String> parameterNames = parameters.keySet();

        int i = 0;
        for (final String parameterName : parameterNames) {

            final Object value = parameters.get(parameterName);

            if (value == null) {
                if (i > 0) {
                    strBuilder.append('&');
                }
                strBuilder.append(UriEscape.escapeUriQueryParam(parameterName));
                i++;
                continue;
            }

            if (!(value instanceof List<?>)) {
                if (i > 0) {
                    strBuilder.append('&');
                }
                strBuilder.append(UriEscape.escapeUriQueryParam(parameterName));
                strBuilder.append('=');
                strBuilder.append(UriEscape.escapeUriQueryParam(value.toString())); // we know it's not null
                i++;
                continue;
            }

            // It is multivalued, so iterate and process each value
            final List<?> values = (List<?>)value;
            final int valuesLen = values.size();
            for (int j = 0; j < valuesLen; j++) {
                final Object valueItem = values.get(j);
                if (i > 0 || j > 0) {
                    strBuilder.append('&');
                }
                strBuilder.append(UriEscape.escapeUriQueryParam(parameterName));
                if (valueItem != null) {
                    strBuilder.append('=');
                    strBuilder.append(UriEscape.escapeUriQueryParam(valueItem.toString()));
                }
            }

            i++;

        }

    }


    // TODO * Is there any kind of mechanism for rewriting URLs at the response?
    protected String processLink(final IExpressionContext context, final String link) {

        if (!(context instanceof ISpringReactiveWebContext)) {
            return link;
        }

        final ServerWebExchange exchange = ((ISpringReactiveWebContext)context).getExchange();
        final ServerHttpResponse response = exchange.getResponse();

        // TODO * Once an URL rewriting mechanism exists, it should be applied here
        return link;

    }


}
