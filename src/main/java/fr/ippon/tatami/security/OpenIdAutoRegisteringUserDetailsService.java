package fr.ippon.tatami.security;

import fr.ippon.tatami.domain.User;
import fr.ippon.tatami.repository.DomainRepository;
import fr.ippon.tatami.service.UserService;
import fr.ippon.tatami.service.util.DomainUtil;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.security.core.SpringSecurityMessageSource;
import org.springframework.security.core.userdetails.AuthenticationUserDetailsService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.openid.OpenIDAttribute;
import org.springframework.security.openid.OpenIDAuthenticationToken;
import org.springframework.stereotype.Component;

import javax.inject.Inject;
import java.util.List;

/**
 * UserDetails Service to be used with OpenId authentication.
 * It auto-registers the user based on its "email" OpenId attribute
 * (that must have been asked to the OpenId provider).
 *
 * @author Fabien Arrault
 */
@Component
public class OpenIdAutoRegisteringUserDetailsService implements
        AuthenticationUserDetailsService<OpenIDAuthenticationToken> {

    private static final String EMAIL_ATTRIBUTE = "email";
    private static final String FIRSTNAME_ATTRIBUTE = "firstname";
    private static final String LASTNAME_ATTRIBUTE = "lastname";
    private static final String FULLNAME_ATTRIBUTE = "fullname";

    private final Log log = LogFactory.getLog(OpenIdAutoRegisteringUserDetailsService.class);

    @Inject
    private UserService userService;

    @Inject
    private DomainRepository domainRepository;

    @Inject
    private TatamiUserDetailsService delegate; // => handles grantedAuthorities

    protected MessageSourceAccessor messages = SpringSecurityMessageSource.getAccessor();

    @Override
    public UserDetails loadUserDetails(OpenIDAuthenticationToken token) throws UsernameNotFoundException {

        String email = getAttributeValue(token, EMAIL_ATTRIBUTE);
        // Important security assumption : here we are trusting the OpenID provider
        // to give us an email that has already been verified to belong to the user

        if (email == null) {
            // TODO : handle this case differently ? ask the user for an email and send it an activation email ?

            String msg = "OpendId response did not contain the user email";
            log.error(msg);
            throw new UsernameNotFoundException(msg);
        }
        // TODO : est-ce nécessaire ? le createUser le test déjà non ?
        if (!email.contains("@")) {
            if (log.isDebugEnabled()) {
                log.debug("User login " + email + " from OpenId response is incorrect.");
            }
            throw new UsernameNotFoundException("OpendId response did not contains a valid user email");
        }

        // Automatically create OpenId users in Tatami :
        UserDetails userDetails;
        try {
            // TODO : replace by "load by OpenId" and check coherence of email ?
            userDetails = delegate.loadUserByUsername(email);
            // ensure that this user has access to its domain if it has been created before
            domainRepository.updateUserInDomain(DomainUtil.getDomainFromLogin(email), DomainUtil.getUsernameFromLogin(email));

        } catch (UsernameNotFoundException e) {
            if (log.isInfoEnabled()) {
                log.info("User with " + email + " doesn't exist yet in Tatami database - creating it...");
            }
            userDetails = getNewlyCreatedUserDetails(token);
        }
        return userDetails;
    }

    private org.springframework.security.core.userdetails.User getNewlyCreatedUserDetails(OpenIDAuthenticationToken token) {
        String login = getAttributeValue(token, EMAIL_ATTRIBUTE);
        String firstName = getAttributeValue(token, FIRSTNAME_ATTRIBUTE);
        String lastName = getAttributeValue(token, LASTNAME_ATTRIBUTE);

        String fullName = getAttributeValue(token, FULLNAME_ATTRIBUTE);
        if (firstName == null && lastName == null) {
            // if we haven't first nor last name, we use fullName as last name to begin with :
            lastName = fullName;
        }

        User user = new User();
        // Note : The email could change... and the OpenId not
        // moreover an OpenId account could potentially be associated with several email addresses
        // so we store it for future use case :
        user.setOpenIdUrl(token.getName());

        user.setLogin(login);
        user.setFirstName(firstName); // can be null
        user.setLastName(lastName);   // can be null
        userService.createUser(user);

        return delegate.getTatamiUserDetails(login, "<NOT_USED>"); // TODO : Is it safe to use a dummy constant for password here ?
    }

    private String getAttributeValue(OpenIDAuthenticationToken token, String name) {
        String value = null;
        for (OpenIDAttribute attribute : token.getAttributes()) {
            if (name.equals(attribute.getName())) {
                List<String> values = attribute.getValues();
                String firstValue = values.isEmpty() ? null : values.iterator().next();
                value = firstValue;
                break;
            }
        }
        return value;
    }

}
