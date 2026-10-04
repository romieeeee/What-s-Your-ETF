package com.d102.wye.core.di

import com.d102.wye.data.repository.EtfRepositoryImpl
import com.d102.wye.data.repository.PortfolioRepositoryImpl
import com.d102.wye.data.repository.SimulationRepositoryImpl
import com.d102.wye.domain.repository.EtfRepository
import com.d102.wye.domain.repository.PortfolioRepository
import com.d102.wye.domain.repository.SimulationRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Release 빌드에서는 기존 네트워크/로컬 DB Repository를 사용한다. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PerformanceRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindEtfRepository(impl: EtfRepositoryImpl): EtfRepository

    @Binds
    @Singleton
    abstract fun bindSimulationRepository(impl: SimulationRepositoryImpl): SimulationRepository

    @Binds
    @Singleton
    abstract fun bindPortfolioRepository(impl: PortfolioRepositoryImpl): PortfolioRepository
}