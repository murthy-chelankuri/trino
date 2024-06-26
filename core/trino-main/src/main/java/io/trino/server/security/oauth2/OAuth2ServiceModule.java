/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.server.security.oauth2;

import com.google.inject.Binder;
import com.google.inject.Inject;
import com.google.inject.Key;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import io.airlift.configuration.AbstractConfigurationAwareModule;
import io.airlift.units.DataSize;
import io.trino.server.ui.OAuth2WebUiInstalled;

import java.time.Duration;

import static com.google.inject.multibindings.OptionalBinder.newOptionalBinder;
import static io.airlift.configuration.ConditionalModule.conditionalModule;
import static io.airlift.configuration.ConfigBinder.configBinder;
import static io.airlift.http.client.HttpClientBinder.httpClientBinder;
import static io.airlift.jaxrs.JaxrsBinder.jaxrsBinder;
import static io.airlift.units.DataSize.Unit.KILOBYTE;
import static io.trino.server.security.oauth2.TokenPairSerializer.ACCESS_TOKEN_ONLY_SERIALIZER;

public class OAuth2ServiceModule
        extends AbstractConfigurationAwareModule
{
    @Override
    protected void setup(Binder binder)
    {
        jaxrsBinder(binder).bind(OAuth2CallbackResource.class);
        newOptionalBinder(binder, OAuth2WebUiInstalled.class);

        configBinder(binder).bindConfig(OAuth2Config.class);
        binder.bind(OAuth2Service.class).in(Scopes.SINGLETON);
        binder.bind(OAuth2TokenHandler.class).to(OAuth2TokenExchange.class).in(Scopes.SINGLETON);
        binder.bind(NimbusHttpClient.class).to(NimbusAirliftHttpClient.class).in(Scopes.SINGLETON);
        newOptionalBinder(binder, OAuth2Client.class)
                .setDefault()
                .to(NimbusOAuth2Client.class)
                .in(Scopes.SINGLETON);
        install(conditionalModule(OAuth2Config.class, OAuth2Config::isEnableDiscovery, this::bindOidcDiscovery, this::bindStaticConfiguration));
        install(conditionalModule(OAuth2Config.class, OAuth2Config::isEnableRefreshTokens, this::enableRefreshTokens, this::disableRefreshTokens));
        httpClientBinder(binder)
                .bindHttpClient("oauth2-jwk", ForOAuth2.class)
                .withConfigDefaults(clientConfig -> clientConfig
                        .setRequestBufferSize(DataSize.of(32, KILOBYTE))
                        .setResponseBufferSize(DataSize.of(32, KILOBYTE)));
    }

    private void enableRefreshTokens(Binder binder)
    {
        install(new JweTokenSerializerModule());
    }

    private void disableRefreshTokens(Binder binder)
    {
        binder.bind(TokenPairSerializer.class).toInstance(ACCESS_TOKEN_ONLY_SERIALIZER);
        newOptionalBinder(binder, Key.get(Duration.class, ForRefreshTokens.class));
    }

    @Singleton
    @Provides
    @Inject
    public TokenRefresher getTokenRefresher(TokenPairSerializer tokenAssembler, OAuth2TokenHandler tokenHandler, OAuth2Client oAuth2Client)
    {
        return new TokenRefresher(tokenAssembler, tokenHandler, oAuth2Client);
    }

    private void bindStaticConfiguration(Binder binder)
    {
        configBinder(binder).bindConfig(StaticOAuth2ServerConfig.class);
        binder.bind(OAuth2ServerConfigProvider.class).to(StaticConfigurationProvider.class).in(Scopes.SINGLETON);
    }

    private void bindOidcDiscovery(Binder binder)
    {
        configBinder(binder).bindConfig(OidcDiscoveryConfig.class);
        binder.bind(OAuth2ServerConfigProvider.class).to(OidcDiscovery.class).in(Scopes.SINGLETON);
    }

    @Override
    public int hashCode()
    {
        return OAuth2ServiceModule.class.hashCode();
    }

    @Override
    public boolean equals(Object obj)
    {
        return obj instanceof OAuth2ServiceModule;
    }
}
