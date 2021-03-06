package com.example.userservice.Controller;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

import com.example.userservice.Exception.AppException;
import com.example.userservice.Exception.ResourceNotFoundException;
import com.example.userservice.Model.Role;
import com.example.userservice.Model.RoleName;
import com.example.userservice.Model.User;
import com.example.userservice.Payload.*;
import com.example.userservice.Repository.RoleRepository;
import com.example.userservice.Repository.UserRepository;
import com.example.userservice.redisService.RedisUtil;
import com.example.userservice.security.CurrentUser;
import com.example.userservice.security.JwtTokenProvider;

import com.example.userservice.security.UserPrincipal;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;


@RestController
@RequestMapping("/user-service")
public class usercontroller {

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    UserRepository userRepository;

    @Autowired
    RoleRepository roleRepository;

    @Autowired
    AuthenticationManager authenticationManager;

    @Autowired
    JwtTokenProvider tokenProvider;

    @Autowired
    Environment env;


    @Autowired
    RedisUtil redisUtil;

    @Value("${app.jwtSecret}") //app properties??? ???????????????
    private String jwtSecret;

    @Value("${app.jwtExpirationInMs}") //????????????
    private int jwtExpirationInMs;



    @GetMapping("/welcome")
    public String welcome(){
        return "welcome User-Service"
                + env.getProperty("local.server.port")
                + env.getProperty("app.jwtSecret")
                + env.getProperty("app.jwtExpirationInMs" )
                ;
    }


/*
    {
        "name":"5555",
         "username" : "5555",
         "email": "5555@5555.com",
         "password":"555555"
    }

    {
    "success": false,
    "message": "Username is already taken!"
    }

    {
    "success": true,
    "message": "User registered successfully"
}
*/
    //????????????
    @PostMapping("/signup")
    public ResponseEntity<?> registerUser(@Valid @RequestBody SignUpRequest signUpRequest) {
        if(userRepository.existsByUsername(signUpRequest.getUsername())) {
            return new ResponseEntity(new ApiResponse(false, "Username is already taken!"),
                    HttpStatus.BAD_REQUEST);
        }
        if(userRepository.existsByEmail(signUpRequest.getEmail())) {
            return new ResponseEntity(new ApiResponse(false, "Email Address already in use!"),
                    HttpStatus.BAD_REQUEST);
        }
        // Creating user's account
        User user = new User(signUpRequest.getName(), signUpRequest.getUsername(),
                signUpRequest.getEmail(), signUpRequest.getPassword());
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        Role userRole = roleRepository.findByName(RoleName.ROLE_USER)
                .orElseThrow(() -> new AppException("User Role not set."));
        user.setRoles(Collections.singleton(userRole));
        int cash = 0;
        user.setCash((long) cash);
        User result = userRepository.save(user);

        URI location = ServletUriComponentsBuilder
                .fromCurrentContextPath().path("/user-service/user/{username}")
                .buildAndExpand(result.getUsername()).toUri();

        // ????????? http://localhost:8081/user-service/user/{username} ????????? ???????????? ??????.
        return ResponseEntity.created(location).body(new ApiResponse(true, "User registered successfully"));
    }


/*
    {
        "usernameOrEmail": "2222",
            "password":"222222"
    }
    {
    "accessToken": "eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiIyIiwiaWF0IjoxNjI0MjI3MDkwLCJleHAiOjE2MjQ4MzE4OTB9.mmRN9MxVJ7xpwSjoRONpoA-0d6yFExciehKyqtSMP58Au3lN_tD2R6x0RCBqQw3Cp7YWWdBPdyo4OZ863Ugz_A",
    "tokenType": "Bearer"
    }

 */
    // ?????????
    @PostMapping("/signin")
    public ResponseEntity<?> authenticateUser(@Valid @RequestBody LoginRequest loginRequest) {

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        loginRequest.getUsernameOrEmail(),
                        loginRequest.getPassword()
                )
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
        String jwt = tokenProvider.generateToken(authentication);
        // refresh ?????? ??????
        String refresh_jwt = tokenProvider.generateRefreshToken(authentication);
        UserPrincipal userPrincipal = (UserPrincipal) authentication.getPrincipal();
        // redis : userid - refresh token ??????
        // System.out.println(Long.toString(userPrincipal.getId()) +" : "+ refresh_jwt);
        redisUtil.setDataExpire(Long.toString(userPrincipal.getId()), refresh_jwt, 1000L * 60 * 60 * 24 );

        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.add("jwt", jwt);
        User user = userRepository.findByUsernameOrEmail(loginRequest.getUsernameOrEmail(), loginRequest.getUsernameOrEmail()).orElse(new User());
        responseHeaders.add("userId", user.getId().toString());
        return ResponseEntity.ok().headers(responseHeaders).body(new JwtAuthenticationResponse(jwt));
    }


    // Access token ?????????
    @GetMapping("/accesstoken_reset")
    public ResponseEntity<?> accesstoken_reset(HttpServletRequest request){
        String bearerToken = request.getHeader("Authorization");
        String jwt = bearerToken.substring(7, bearerToken.length());
        try {
            Claims claims = Jwts.parser().setSigningKey(jwtSecret).parseClaimsJws(jwt).getBody();
        } catch (ExpiredJwtException ex){

            Long userId = Long.parseLong(ex.getClaims().get("userId").toString());
            User user = userRepository.getById(userId);

            Date now = new Date();
            Date expiryDate = new Date(now.getTime() + jwtExpirationInMs); //?????? ??????
            List<GrantedAuthority> authorities = user.getRoles().stream().map(role ->
                    new SimpleGrantedAuthority(role.getName().name())
            ).collect(Collectors.toList());

            String new_jwt = Jwts.builder()
                    .claim("userId", Long.toString(userId))
                    .claim("userAuth", authorities.toString()) //?????????
                    .setIssuedAt(new Date()) //?????? ?????? ??????
                    .setExpiration(expiryDate) //?????? ??????
                    .signWith(SignatureAlgorithm.HS512, jwtSecret) //????????? ????????????, secret??? ??????
                    .compact();

            // System.out.println(new_jwt);
            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.add("jwt", new_jwt);
            responseHeaders.add("userId", user.getId().toString());
            return ResponseEntity.ok().headers(responseHeaders).body(new JwtAuthenticationResponse(new_jwt));
        }
        return null;
    }



    //?????? ??????
    @PostMapping("/chargeCash")
    public void chargeCash(@Valid @RequestBody Map<String, Long> stringLongMap, @CurrentSecurityContext(expression="authentication.name") String username){
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User", "username", username));
        Long cash = user.getCash();
        user.setCash(cash + stringLongMap.get("cash_amount"));
        userRepository.save(user);
    }

    //????????? ??????
    @GetMapping("/rentToon")
    public void rentToon(@Valid @CurrentSecurityContext(expression="authentication.name") String username){
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User", "username", username));
        Long cash = user.getCash();
        user.setCash(cash - 10);
        userRepository.save(user);
    }

/*
    http://localhost:8081/user-service/checkUsernameAvailability?username=admin
    {
    "available": true
    }
 */
    //???????????? ???, ??????????????? ???????????? ?????? & ????????????
    @GetMapping("/checkUsernameAvailability")
    public UserIdentityAvailability checkUsernameAvailability(@RequestParam(value = "username") String username) {
        Boolean isAvailable = !userRepository.existsByUsername(username);
        return new UserIdentityAvailability(isAvailable);
    }



    /*
    http://localhost:8081/user-service/checkEmailAvailability?email=admin@admin.com
    {
    "available": true
    }
 */
    // ???????????? ???, ???????????? ???????????? ?????? & ????????????
    @GetMapping("/checkEmailAvailability")
    public UserIdentityAvailability checkEmailAvailability(@RequestParam(value = "email") String email) {
        Boolean isAvailable = !userRepository.existsByEmail(email);
        return new UserIdentityAvailability(isAvailable);
    }



/*
http://localhost:8081/user-service/user/2222
{
    "id": 2,
    "username": "2222",
    "name": "2222",
    "cash": 80
}
 */
    // ?????? ????????? (Profile)??????  User(??????) / Username(ID) / Cash (?????? ?????? ??????) ??????
    @GetMapping("/user/{username}")
    public UserProfile getUserProfile(@PathVariable(value = "username") String username, @CurrentSecurityContext(expression="authentication.name") String username1) {
        if(username1.equals(username)) {
            User user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new ResourceNotFoundException("User", "username", username));
            UserProfile userProfile = new UserProfile(user.getId(), user.getUsername(), user.getName(), user.getCash());
            return userProfile;
        }
        else return null;
    }



    /*
    {
        "id": 7,
            "name": "5555",
            "username": "5555",
            "authorities": [
        {
            "authority": "ROLE_USER"
        }
    ],
        "enabled": true,
            "accountNonLocked": true,
            "accountNonExpired": true,
            "credentialsNonExpired": true
    }

 */
    // ?????? ???????????? ??????
    @GetMapping("/user/me")
    public UserPrincipal getCurrentUser(@CurrentUser UserPrincipal currentUser) {
        UserPrincipal userPrincipal = new UserPrincipal(currentUser.getId(), currentUser.getName(), currentUser.getUsername(),  currentUser.getAuthorities());
        return userPrincipal;
    }



 /*
  850
  */
    // ????????? ?????? ?????? ?????? ??????
    @GetMapping("/CheckCash/me")
    public String CheckCash( @CurrentSecurityContext(expression="authentication.name") String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User", "username", username));
        Long coin = user.getCash();
        return coin.toString();
    }

    /*
    [
    {
        "createdAt": "2021-06-07T03:47:45",
            "updatedAt": "2021-06-07T22:46:19",
            "id": 1,
            "name": "1111",
            "username": "1111",
            "email": "1111@1111.com",
            "password": "$2a$10$zHsh4BOXoS61n6e2hu7Vz./J2JMTxP0NPhjAX2BsxE6JRleVmYOA.",
            "cash": 950,
            "roles": [
        {
            "id": 2,
                "name": "ROLE_ADMIN"
        }
        ]
    },
    {
        "createdAt": "2021-06-07T09:33:18",
            "updatedAt": "2021-06-20T18:28:46",
            "id": 2,
            "name": "2222",
            "username": "2222",
            "email": "2222@2222.com",
            "password": "$2a$10$tX9aQPZ22.q8hK.UHwoDHuD9sP13R5jX4mb1gPE9xjSpfTloWPjWm",
            "cash": 80,
            "roles": [
        {
            "id": 1,
                "name": "ROLE_AUTHOR"
        }
        ]
    }]
    */

    // ?????? ?????? ??????
    @GetMapping("/SelectUser/all")
    @PreAuthorize("hasAnyRole('ADMIN')")
    public Collection<User> SelectUserAll() {
        return userRepository.findAll();
    }

/*
http://localhost:8000/user-service/userinfoEdit/5555
{
    "name":"5544",
    "username" : "5533",
    "email": "5533@5555.com",
    "password":"555555"
}
{
    "success": true,
    "message": "User updated successfully"
}
 */
    // ???????????? ??????
    @PutMapping("/userinfoEdit/{username}")
    public ResponseEntity<?> userinfoEdit(@PathVariable(value = "username") String username, @Valid @RequestBody SignUpRequest signUpRequest){
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User", "username", username));

        if(userRepository.existsByUsername(signUpRequest.getUsername())) {
            if(!signUpRequest.getUsername().equals(user.getUsername())) {
                return new ResponseEntity(new ApiResponse(false, "Username is already taken!"),
                        HttpStatus.BAD_REQUEST);
            }
        }
        if(userRepository.existsByEmail(signUpRequest.getEmail())) {
            if(!signUpRequest.getEmail().equals(user.getEmail())) {
                return new ResponseEntity(new ApiResponse(false, "Email Address already in use!"),
                        HttpStatus.BAD_REQUEST);
            }
        }
        // Updating user's account
        user.setName(signUpRequest.getName());
        user.setUsername(signUpRequest.getUsername());
        user.setEmail(signUpRequest.getEmail());
        user.setPassword(passwordEncoder.encode(signUpRequest.getPassword()));
        user.setRoles(user.getRoles());
        user.setCash(user.getCash());
        User result = userRepository.save(user);
        URI location = ServletUriComponentsBuilder
                .fromCurrentContextPath().path("/user-service/user/{username}")
                .buildAndExpand(result.getUsername()).toUri();
        return ResponseEntity.created(location).body(new ApiResponse(true, "User updated successfully"));
    }

/*
http://localhost:8000/user-service/deleteUser/5555
 */
    // ???????????? ??????
    @DeleteMapping("/deleteUser/{username}")
    public void deleteUser(@PathVariable String username){
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User", "username", username));
        userRepository.delete(user);
    }





}
