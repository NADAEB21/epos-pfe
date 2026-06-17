import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, Validators, ReactiveFormsModule } from '@angular/forms';
import { ProfilService } from '../../core/profil/profil.service';
import { UserResponse } from '../../core/api/models';

@Component({
  selector: 'app-profil',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './profil.component.html',
  styleUrl: './profil.component.scss'
})
export class ProfilComponent implements OnInit {
  profilForm: FormGroup;
  passwordForm: FormGroup;
  user: UserResponse | null = null;

  constructor(private profilService: ProfilService, private fb: FormBuilder) {
    this.profilForm = this.fb.group({
      nom: ['', Validators.required],
      prenom: ['', Validators.required],
      email: [{value: '', disabled: true}, [Validators.required, Validators.email]]
    });

    this.passwordForm = this.fb.group({
      oldPassword: ['', Validators.required],
      newPassword: ['', [Validators.required, Validators.minLength(6)]]
    });
  }

  ngOnInit(): void {
    this.profilService.getConnectedUser().subscribe(u => {
      this.user = u;
      this.profilForm.patchValue(u);
    });
  }

  saveProfil() {
    if (this.profilForm.valid) {
      this.profilService.updateProfil(this.profilForm.value).subscribe(() => alert('Profil mis à jour !'));
    }
  }

  changePassword() {
    if (this.passwordForm.valid) {
      alert('Demande de changement de mot de passe envoyée.');
      this.passwordForm.reset();
    }
  }
}