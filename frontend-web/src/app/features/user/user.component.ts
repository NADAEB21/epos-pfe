import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { UserResponse } from '../../core/api/models';

export interface UserUI extends UserResponse {
  role: string;
}

@Component({
  selector: 'app-user',
  standalone: true,
  imports: [CommonModule, FormsModule, ReactiveFormsModule],
  templateUrl: './user.component.html',
  styleUrl: './user.component.scss'
})
export class UserComponent implements OnInit {
  userForm: FormGroup;
  users: UserUI[] = []; // La liste qui sera remplie par le formulaire
  filteredUsers: UserUI[] = [];
  
  // Variables de recherche
  searchTerm: string = '';
  roleFilter: string = '';

  isEditing = false;
  currentUserId: number | null = null;

  constructor(private fb: FormBuilder) {
    // Initialisation du formulaire
    this.userForm = this.fb.group({
      nom: ['', Validators.required],
      prenom: ['', Validators.required],
      email: ['', [Validators.required, Validators.email]],
      role: ['Évaluateur', Validators.required],
      isActive: [true, Validators.required]
    });
  }

  ngOnInit(): void {
    // On peut laisser quelques exemples par défaut ou commencer à vide
    this.users = [
      { id: 1, nom: 'Ben Salem', prenom: 'Amina', email: 'amina@epos.tn', isActive: true, role: 'Évaluateur', createdAt: null }
    ];
    this.filteredUsers = [...this.users];
  }

  // Ajouter ou Modifier un utilisateur
  onSubmit() {
    if (this.userForm.invalid) return;

    if (this.isEditing && this.currentUserId !== null) {
      // Logique Modification
      const index = this.users.findIndex(u => u.id === this.currentUserId);
      this.users[index] = { id: this.currentUserId, ...this.userForm.value, createdAt: null };
      this.isEditing = false;
      this.currentUserId = null;
    } else {
      // Logique Ajout
      const newUser: UserUI = {
        id: Date.now(), // ID temporaire
        ...this.userForm.value,
        createdAt: new Date().toISOString()
      };
      this.users.push(newUser);
    }

    this.userForm.reset({ role: 'Évaluateur', isActive: true });
    this.applyFilters();
  }

  onEdit(user: UserUI) {
    this.isEditing = true;
    this.currentUserId = user.id;
    this.userForm.patchValue(user);
  }

  onDelete(id: number) {
    if(confirm('Supprimer cet utilisateur ?')) {
      this.users = this.users.filter(u => u.id !== id);
      this.applyFilters();
    }
  }

  applyFilters() {
    this.filteredUsers = this.users.filter(u => {
      const search = this.searchTerm.toLowerCase();
      const matchesSearch = (u.nom + u.prenom + u.email).toLowerCase().includes(search);
      const matchesRole = this.roleFilter === '' || u.role === this.roleFilter;
      return matchesSearch && matchesRole;
    });
  }
}