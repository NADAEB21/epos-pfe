import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map, of } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ExamApiService } from '../../core/api/exam-api.service';
import { DirectoryApiService } from '../../core/api/directory-api.service';
import { ExamenResponse } from '../../core/api/models';

export interface ExamStats {
  BROUILLON: number; CONFIGURE: number; EN_COURS: number; TERMINE: number; ARCHIVE: number;
}

@Injectable({ providedIn: 'root' })
export class AdminHomeService {
  private readonly http = inject(HttpClient);
  private readonly examApi = inject(ExamApiService);
  private readonly directoryApi = inject(DirectoryApiService);
  private readonly baseUrl = `${environment.apiBaseUrl}/examens`;

  getMatieres(): Observable<{ data: any[] }> {
    return this.directoryApi.listMatieres().pipe(map(res => ({ data: res })));
  }

  getExamens(page: number, size: number): Observable<{ data: any }> {
    return this.examApi.listExamens({ page, size, sort: 'dateExamen,desc' }).pipe(map(res => ({ data: res })));
  }

  // Chiffres pour les nouvelles cartes
  getUsersCount(): Observable<number> { return of(7); }
  getStationsCount(): Observable<number> { return of(9); }

  calculateStats(exams: ExamenResponse[]): ExamStats {
    const stats: ExamStats = { BROUILLON: 0, CONFIGURE: 0, EN_COURS: 0, TERMINE: 0, ARCHIVE: 0 };
    exams.forEach(ex => { if (ex.statut in stats) stats[ex.statut as keyof ExamStats]++; });
    return stats;
  }
}