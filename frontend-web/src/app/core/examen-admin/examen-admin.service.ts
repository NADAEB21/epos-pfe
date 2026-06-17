import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { ExamenResponse, PageResponse, StatutExamen, MatiereResponse } from '../api/models';
import { DirectoryApiService } from '../../core/api/directory-api.service'; // Assurez-vous du chemin
import { ExamApiService } from '../../core/api/exam-api.service'; // Assurez-vous du chemin

export type ExamStats = Record<StatutExamen, number>;

@Injectable({
  providedIn: 'root'
})
export class ExamenAdminService {
  private readonly http = inject(HttpClient);
  private readonly directoryApi = inject(DirectoryApiService);
  private readonly examApi = inject(ExamApiService);

  private apiUrl = 'http://localhost:8080/api/examens';

  constructor() {}

  /**
   * ✅ RÉPARÉ : Utilise la même méthode que AdminHomeService
   */
  getMatieres(): Observable<{ data: MatiereResponse[] }> {
    return this.directoryApi.listMatieres().pipe(
      map(res => ({ data: res }))
    );
  }

  getExamens(page: number = 0, size: number = 50): Observable<{ data: PageResponse<ExamenResponse> }> {
    return this.examApi.listExamens({ page, size, sort: 'dateExamen,desc' }).pipe(
      map(res => ({ data: res }))
    );
  }

  creerExamen(examen: any): Observable<any> {
    return this.http.post<any>(this.apiUrl, examen);
  }

  modifierExamen(id: number, examen: any): Observable<any> {
    return this.http.patch<any>(`${this.apiUrl}/${id}`, examen);
  }

  supprimerExamen(id: number): Observable<any> {
    return this.http.delete<any>(`${this.apiUrl}/${id}`);
  }

  calculateStats(examens: ExamenResponse[]): ExamStats {
    const stats: ExamStats = { BROUILLON: 0, CONFIGURE: 0, EN_COURS: 0, TERMINE: 0, ARCHIVE: 0 };
    examens.forEach(e => {
      if (e.statut in stats) stats[e.statut]++;
    });
    return stats;
  }
}