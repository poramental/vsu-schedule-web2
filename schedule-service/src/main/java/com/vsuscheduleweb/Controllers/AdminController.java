package com.vsuscheduleweb.Controllers;


import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;



@Controller
@RequestMapping()
public class AdminController {

    @GetMapping(value = "/schedule/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public String getAdminPage(Authentication authentication){
        return "vsuAdminApp";
    }

    @GetMapping("/")
    public String getRoot(Authentication authentication){
        return "redirect:/schedule/admin";
    }
}
