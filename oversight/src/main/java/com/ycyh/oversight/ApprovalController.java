package com.ycyh.oversight;
import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
@Controller
public class ApprovalController {
 private final ApplicationService service;
 public ApprovalController(ApplicationService service){this.service=service;}
 @GetMapping("/login")public String login(){return "login";}
 @GetMapping("/")public String list(Model model){model.addAttribute("applications",service.list());return "approvals";}
 @GetMapping("/approvals/{id}")public String detail(@PathVariable String id,Model model){model.addAttribute("app",service.detail(id));return "detail";}
 @PostMapping("/approvals/{id}/approve")public String approve(@PathVariable String id,Principal p,@RequestParam(defaultValue="")String reason){service.decide(id,p.getName(),true,reason);return "redirect:/approvals/"+id;}
 @PostMapping("/approvals/{id}/reject")public String reject(@PathVariable String id,Principal p,@RequestParam(defaultValue="")String reason){service.decide(id,p.getName(),false,reason);return "redirect:/approvals/"+id;}
}